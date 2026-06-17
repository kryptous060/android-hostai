package com.wannaphong.hostai

import android.content.Context
import android.util.Base64
import com.google.ai.edge.litertlm.Content
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import io.javalin.Javalin
import io.javalin.http.Context as JavalinContext
import kotlinx.coroutines.*
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.thread.QueuedThreadPool
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Data class to store chat completion information.
 * Messages are stored as Map<String, Any> to support multimodal content.
 * Content can be either String (simple text) or List (multimodal content parts).
 */
data class StoredCompletion(
    val id: String,
    val obj: String,
    val created: Long,
    val model: String,
    val messages: List<Map<String, Any>>,
    val responseContent: String,
    var metadata: Map<String, Any>?
)

/**
 * OpenAI-compatible API server implementation using Javalin.
 * Implements the following endpoints:
 * - POST /v1/chat/completions - Chat completions (OpenAI format)
 * - POST /v1/completions - Text completions (OpenAI format)
 * - GET /v1/models - List available models
 * - GET /chat - Chat UI interface
 */
class OpenAIApiServer(
    private val port: Int,
    private val model: LlamaModel,
    private val context: Context
) {
    
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    
    // Coroutine scope for streaming responses
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var app: Javalin? = null
    
    // Storage for chat completions with store=true
    private val storedCompletions = ConcurrentHashMap<String, StoredCompletion>()
    
    // Settings manager for feature toggles
    private val settingsManager = SettingsManager(context)
    
    // Semaphore to limit concurrent model-inference requests.
    // Initialised in start() from the configured max-concurrency value.
    // fair=true ensures requests are queued in FIFO order (OpenAI-like behaviour).
    private var requestSemaphore = Semaphore(SettingsManager.DEFAULT_MAX_CONCURRENCY, true)
    
    // Request logger (singleton)
    private val requestLogger by lazy { RequestLogger.getInstance(context) }
    
    companion object {
        private const val TAG = "OpenAIApiServer"
        // Maximum request body size (10 MB) to prevent memory exhaustion attacks
        private const val MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024

        // Jetty thread-pool tuning: keep a small number of threads warm so that
        // the very first request (and requests after a quiet period) do not incur
        // thread-creation latency on Android.
        private const val JETTY_MIN_THREADS = 4
        private const val JETTY_MAX_THREADS = 20
        private const val JETTY_IDLE_TIMEOUT_MS = 60_000
    }
    
    fun start() {
        try {
                        io.javalin.util.ConcurrencyUtil.useLoom = false
            
            // Initialise the semaphore with the current max-concurrency setting.
            val maxConcurrency = settingsManager.getMaxConcurrency()
                .coerceAtLeast(1)
            requestSemaphore = Semaphore(maxConcurrency, true)
            LogManager.i(TAG, "Max concurrency set to $maxConcurrency")

            // Pre-warm a small pool of Jetty worker threads so that the first
            // request (and requests after idle periods) do not pay the cost of
            // thread creation on Android.  This is the primary fix for the
            // 5-10 second latency seen when the server appears "slow to start".
            val threadPool = QueuedThreadPool(JETTY_MAX_THREADS, JETTY_MIN_THREADS, JETTY_IDLE_TIMEOUT_MS)
            threadPool.isDaemon = true
            threadPool.name = "hostai-jetty"
            
            app = Javalin.create { config ->
                // Configure Javalin
                config.maxRequestSize = MAX_REQUEST_BODY_SIZE.toLong()
                config.showJavalinBanner = false
            }.apply {
                // Health check
                get("/health") { ctx -> handleHealth(ctx) }
                
                // Model endpoints
                get("/v1/models") { ctx -> handleModels(ctx) }
                
                // Completion endpoints
                post("/v1/chat/completions") { ctx -> handleChatCompletions(ctx) }
                post("/v1/completions") { ctx -> handleCompletions(ctx) }
                
                // Stored chat completions endpoints
                get("/v1/chat/completions/{completion_id}") { ctx -> handleGetStoredCompletion(ctx) }
                get("/v1/chat/completions/{completion_id}/messages") { ctx -> handleGetStoredCompletionMessages(ctx) }
                post("/v1/chat/completions/{completion_id}") { ctx -> handleUpdateStoredCompletion(ctx) }
                
                // UI endpoints
                get("/") { ctx -> handleRoot(ctx) }
                get("/chat") { ctx -> handleChatUI(ctx) }
                get("/assets/{fileName}") { ctx -> handleAssets(ctx) }
                
                // Exception handler
                exception(Exception::class.java) { e, ctx ->
                    LogManager.e(TAG, "Error handling request", e)
                    val errorResponse = mapOf(
                        "error" to mapOf("message" to (e.message ?: "Internal server error"))
                    )
                    ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
                }
            }.start(port)
            
            LogManager.i(TAG, "Javalin server started on port $port")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start Javalin server", e)
            throw e
        }
    }
    
    fun stop() {
        try {
            app?.stop()
            serverScope.cancel() // Cancel all streaming coroutines
            LogManager.i(TAG, "Javalin server stopped")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error stopping server", e)
        }
    }
    
    /**
     * Check if an endpoint is enabled and return error if not
     */
    private fun checkEndpointEnabled(ctx: JavalinContext, endpointName: String, isEnabled: Boolean): Boolean {
        if (!isEnabled) {
            val errorResponse = mapOf(
                "error" to mapOf("message" to "$endpointName endpoint is disabled in settings")
            )
            ctx.status(403).contentType("application/json").result(gson.toJson(errorResponse))
            LogManager.w(TAG, "$endpointName endpoint accessed but is disabled")
            return false
        }
        return true
    }
    
    /**
     * Log request if logging is enabled
     */
    private fun logRequestIfEnabled(
        ctx: JavalinContext,
        endpoint: String,
        requestBody: String,
        responseBody: String
    ) {
        if (settingsManager.isLoggingEnabled()) {
            val ipAddress = ctx.ip()
            requestLogger.logRequest(ipAddress, endpoint, requestBody, responseBody)
        }
    }
    
    /**
     * Get all stored completions
     */
    fun getStoredCompletions(): List<StoredCompletion> {
        return storedCompletions.values.toList().sortedByDescending { it.created }
    }
    
    /**
     * Get a specific stored completion by ID
     */
    fun getStoredCompletionById(id: String): StoredCompletion? {
        return storedCompletions[id]
    }
    
    /**
     * Clear all stored completions
     */
    fun clearAllStoredCompletions(): Int {
        val count = storedCompletions.size
        storedCompletions.clear()
        LogManager.i(TAG, "Cleared $count stored completions")
        return count
    }
    
    /**
     * Delete a specific stored completion
     */
    fun deleteStoredCompletion(id: String): Boolean {
        val removed = storedCompletions.remove(id)
        if (removed != null) {
            LogManager.i(TAG, "Deleted stored completion: $id")
            return true
        }
        return false
    }
    
    private fun handleHealth(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /health")
        
        val health = mapOf(
            "status" to "ok",
            "model_loaded" to model.isModelLoaded()
        )
        
        ctx.contentType("application/json").result(gson.toJson(health))
    }
    
    private fun handleModels(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /v1/models")
        
        val models = mapOf(
            "object" to "list",
            "data" to listOf(
                mapOf(
                    "id" to model.getModelName(),
                    "object" to "model",
                    "created" to System.currentTimeMillis() / 1000,
                    "owned_by" to "hostai"
                )
            )
        )
        
        ctx.contentType("application/json").result(gson.toJson(models))
    }
    
    private fun handleRoot(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /")
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>HostAI - OpenAI Compatible API</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 40px; }
                    h1 { color: #6200EE; }
                    .endpoint { background: #f5f5f5; padding: 10px; margin: 10px 0; border-radius: 5px; }
                    .chat-link { background: #6200EE; color: white; padding: 15px 20px; display: inline-block; margin: 20px 0; text-decoration: none; border-radius: 5px; font-weight: bold; }
                    .chat-link:hover { background: #3700B3; }
                </style>
            </head>
            <body>
                <h1>HostAI - OpenAI Compatible API Server</h1>
                <p>Server is running on port $port</p>
                <a href="/chat" class="chat-link">Open Chat UI</a>
                <h2>Available Endpoints:</h2>
                <div class="endpoint">
                    <strong>GET /v1/models</strong><br>
                    List available models
                </div>
                <div class="endpoint">
                    <strong>POST /v1/chat/completions</strong><br>
                    Chat completion endpoint (OpenAI compatible)<br>
                    <em>Set store=true to persist completion for later retrieval</em>
                </div>
                <div class="endpoint">
                    <strong>GET /v1/chat/completions/{completion_id}</strong><br>
                    Get a stored chat completion (only for completions with store=true)
                </div>
                <div class="endpoint">
                    <strong>GET /v1/chat/completions/{completion_id}/messages</strong><br>
                    Get messages from a stored chat completion
                </div>
                <div class="endpoint">
                    <strong>POST /v1/chat/completions/{completion_id}</strong><br>
                    Update metadata for a stored chat completion
                </div>
                <div class="endpoint">
                    <strong>POST /v1/completions</strong><br>
                    Text completion endpoint (OpenAI compatible)
                </div>
                <div class="endpoint">
                    <strong>GET /health</strong><br>
                    Health check endpoint
                </div>
                <div class="endpoint">
                    <strong>GET /chat</strong><br>
                    Web-based chat interface
                </div>
            </body>
            </html>
        """.trimIndent()
        
        ctx.html(html)
    }
    
    private fun handleChatUI(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /chat")
        
        // Check if web chat UI is enabled
        if (!checkEndpointEnabled(ctx, "Web Chat UI", settingsManager.isWebChatEnabled())) {
            return
        }
        
        try {
            val inputStream = context.assets.open("index.html")
            val html = inputStream.bufferedReader().use { it.readText() }
            ctx.html(html)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading chat UI", e)
            ctx.status(500).html(
                "<html><body><h1>Error loading chat UI</h1><p>${e.message}</p></body></html>"
            )
        }
    }
    
    private fun handleAssets(ctx: JavalinContext) {
        val fileName = ctx.pathParam("fileName")
        LogManager.d(TAG, "Handling /assets/$fileName")
        
        try {
            // Security: Prevent path traversal attacks
            if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains("\\")) {
                LogManager.w(TAG, "Rejected potential path traversal attempt: $fileName")
                ctx.status(403).result("Invalid asset path")
                return
            }
            
            // Determine MIME type based on file extension
            val mimeType = when {
                fileName.endsWith(".ico") -> "image/x-icon"
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".html") -> "text/html"
                fileName.endsWith(".css") -> "text/css"
                fileName.endsWith(".js") -> "application/javascript"
                else -> "application/octet-stream"
            }
            
            val inputStream = context.assets.open(fileName)
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            ctx.contentType(mimeType).result(bytes)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error loading asset: $fileName", e)
            ctx.status(404).result("Asset not found")
        }
    }
    
    private fun handleChatCompletions(ctx: JavalinContext) {
        LogManager.d(TAG, "Handling /v1/chat/completions")
        
        // Check if chat completions endpoint is enabled
        if (!checkEndpointEnabled(ctx, "Chat Completions", settingsManager.isChatCompletionsEnabled())) {
            return
        }
        
        try {
            val bodyText = ctx.body()
            
            // Security: Check content length
            if (bodyText.length > MAX_REQUEST_BODY_SIZE) {
                LogManager.w(TAG, "Request body too large: ${bodyText.length} bytes")
                val errorResponse = mapOf(
                    "error" to mapOf("message" to "Request body too large")
                )
                ctx.status(413).contentType("application/json").result(gson.toJson(errorResponse))
                return
            }
            
            val request = gson.fromJson(bodyText, JsonObject::class.java)
            
            LogManager.i(TAG, "Chat completion request received")
            
            // Extract parameters
            val messages = request.getAsJsonArray("messages")
            val stream = request.get("stream")?.asBoolean ?: false
            val store = request.get("store")?.asBoolean ?: false
            val metadata = parseMetadata(request.get("metadata")?.asJsonObject)
            
            // Extract session ID using helper method
            val sessionId = extractSessionId(ctx, request)
            
            LogManager.d(TAG, "Using session ID: $sessionId, store: $store")
            
            // Build generation config from request parameters
            val config = extractGenerationConfig(request)
            
            // Build content from messages (either String prompt or List<Content> for multimodal)
            val contents = buildContentsFromMessages(messages)
            
            // Log preview
            if (contents is String) {
                val promptPreview = if (contents.length > 100) contents.take(100) + "..." else contents
                LogManager.d(TAG, "Prompt preview: $promptPreview")
            } else {
                LogManager.d(TAG, "Multimodal content with ${(contents as List<*>).size} parts")
            }
            LogManager.d(TAG, "Chat completion - stream: $stream, maxTokens: ${config.maxTokens}, temp: ${config.temperature}")
            
            // Acquire a permit before running inference. If max concurrency is reached the
            // calling thread blocks here until a permit becomes available (FIFO queue).
            LogManager.d(TAG, "Acquiring concurrency permit (available: ${requestSemaphore.availablePermits()}, queue depth: ${requestSemaphore.queueLength})")
            requestSemaphore.acquire()
            LogManager.d(TAG, "Concurrency permit acquired for chat completion")
            try {
                if (stream) {
                    handleChatStreamingResponse(ctx, contents, config, sessionId, messages, store, metadata, bodyText)
                } else {
                    handleChatNonStreamingResponse(ctx, contents, config, sessionId, messages, store, metadata, bodyText)
                }
            } finally {
                requestSemaphore.release()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling chat completions", e)
            val errorResponse = mapOf(
                "error" to mapOf("message" to (e.message ?: "Internal server error"))
            )
            ctx.status(500).contentType("application/json").result(gson.toJson(errorResponse))
        }
    }
    
    private fun handleChatNonStreamingResponse(
        ctx: JavalinContext,
        contents: Any,  // Either String or List<Content>
        config: GenerationConfig,
        sessionId: String,
        messages: com.google.gson.JsonArray,
        store: Boolean,
        metadata: Map<String, Any>?,
        bodyText: String
    ) {
        // Generate response with session ID - handle both String and multimodal content
        val completion = if (contents is String) {
            model.generate(contents, config, sessionId)
        } else {
            @Suppress("UNCHECKED_CAST")
            model.generateWithContents(contents as List<Content>, config, sessionId)
        }
        
        val promptTokens = when (contents) {
            is String -> contents.split(" ").size
            else -> {
                // Estimate tokens for multimodal content
                // Count text parts + fixed cost per image/audio
                @Suppress("UNCHECKED_CAST")
                val contentList = contents as List<Content>
                contentList.sumOf { content ->
                    when (content) {
                        is Content.Text -> content.toString().split(" ").size
                        is Content.ImageBytes -> 85  // Typical image token cost (based on OpenAI's 85 tokens per low-detail image)
                        is Content.AudioBytes -> 50  // Estimate for audio
                        else -> 10
                    }
                }
            }
        }
        val completionTokens = completion.split(" ").size
        
        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        
        // Store completion if store parameter is true
        if (store) {
            val messagesList = messages.map { element ->
                val msgObj = element.asJsonObject
                val role = msgObj.get("role")?.asString ?: ""
                val contentElement = msgObj.get("content")
                
                // Preserve the original content structure (string or array for multimodal)
                val content: Any = when {
                    contentElement == null -> ""
                    contentElement.isJsonPrimitive && contentElement.asJsonPrimitive.isString -> {
                        contentElement.asString
                    }
                    contentElement.isJsonArray -> {
                        // Store multimodal content as a list of maps 
