package com.wannaphong.hostai

import android.content.Context
import android.util.Base64
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Part
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
    private val context: Context,
    private val modelRunnerProvider: () -> Any? // Replace with your exact ModelRunner type if explicit
) {
    private var app: Javalin? = null
    private val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    companion object {
        private const val TAG = "OpenAIApiServer"
   
    }
    fun start() {
        if (app != null) {
            LogManager.w(TAG, "Server is already running.")
            return
        }

        app = Javalin.create { config ->
            config.bundledPlugins.enableCors { cors ->
                cors.addRule { it.anyHost() }
            }
            config.jetty.server {
                val pool = QueuedThreadPool(20, 4, 60000)
                Server(pool)
            }
        }.apply {
            // Registering Endpoints
            post("/v1/chat/completions") { ctx -> handleChatCompletions(ctx) }
            post("/v1/completions") { ctx -> handleCompletions(ctx) }
            get("/v1/models") { ctx -> handleModels(ctx) }
            get("/chat") { ctx -> handleChatUi(ctx) }
            
            exception(Exception::class.java) { e, ctx ->
                LogManager.e(TAG, "Unhandled exception in server: ${e.message}", e)
                ctx.status(500).json(mapOf("error" to (e.message ?: "Internal Server Error")))
            }
        }.start(port)
        
        LogManager.i(TAG, "Server started successfully on port $port")
    }

    fun stop() {
        try {
            app?.stop()
            app = null
            serverScope.cancel()
            LogManager.i(TAG, "Server stopped successfully.")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error while stopping server", e)
        }
    
    }
    private fun handleChatCompletions(ctx: JavalinContext) {
        val bodyString = ctx.body()
        val requestMap = try {
            gson.fromJson<Map<String, Any>>(bodyString, object : TypeToken<Map<String, Any>>() {}.type)
        } catch (e: Exception) {
            ctx.status(400).json(mapOf("error" to "Invalid JSON payload"))
            return
        }

        val messages = requestMap["messages"] as? List<Map<String, Any>>
        if (messages == null || messages.isEmpty()) {
            ctx.status(400).json(mapOf("error" to "Missing or empty 'messages' parameter"))
            return
        }

        val stream = requestMap["stream"] as? Boolean ?: false
        val modelName = requestMap["model"] as? String ?: "local-model"

        if (stream) {
            handleChatStreamingResponse(ctx, messages, modelName, requestMap)
        } else {
            handleChatNonStreamingResponse(ctx, messages, modelName, requestMap)
        }
  
    }
    private fun handleChatNonStreamingResponse(
        ctx: JavalinContext,
        messages: List<Map<String, Any>>,
        modelName: String,
        requestMap: Map<String, Any>
    ) {
        val contents = buildContentsFromMessages(messages)
        if (contents.isEmpty()) {
            ctx.status(400).json(mapOf("error" to "Failed to parse valid prompt content from messages"))
            return
        }

        runBlocking {
            try {
                // Adjust to match your model runner's inference syntax
                val runner = modelRunnerProvider() ?: throw IllegalStateException("Model is not loaded")
                
                // Example pseudo-call to generation backend:
                // val responseText = runner.generate(contents)
                val responseText = "This is a placeholder response. Integrate your model's inference call here."

                val responseId = "chatcmpl-${System.currentTimeMillis()}"
                val responseBody = mapOf(
                    "id" to responseId,
                    "object" to "chat.completion",
                    "created" to System.currentTimeMillis() / 1000,
                    "model" to modelName,
                    "choices" to listOf(
                        mapOf(
                            "index" to 0,
                            "message" to mapOf(
                                "role" to "assistant",
                                "content" to responseText
                            ),
                            "finish_reason" to "stop"
                        )
                    ),
                    "usage" to mapOf(
                        "prompt_tokens" to 0,
                        "completion_tokens" to 0,
                        "total_tokens" to 0
                    )
                )

                ctx.contentType("application/json").result(gson.toJson(responseBody))
            } catch (e: Exception) {
                LogManager.e(TAG, "Error generating non-streaming response", e)
                ctx.status(500).json(mapOf("error" to (e.message ?: "Inference failure")))
            }
        }
    }
    private fun handleChatStreamingResponse(
        ctx: JavalinContext,
        messages: List<Map<String, Any>>,
        modelName: String,
        requestMap: Map<String, Any>
    ) {
        val contents = buildContentsFromMessages(messages)
        if (contents.isEmpty()) {
            ctx.status(400).json(mapOf("error" to "Failed to parse content for streaming"))
            return
        }

        ctx.contentType("text/event-stream")
        ctx.res().setCharacterEncoding("UTF-8")
        ctx.res().setHeader("Cache-Control", "no-cache")
        ctx.res().setHeader("Connection", "keep-alive")

        val writer = ctx.res().writer
        val responseId = "chatcmpl-${System.currentTimeMillis()}"

        runBlocking {
            try {
                val runner = modelRunnerProvider() ?: throw IllegalStateException("Model runner unavailable")

                // Emulate chunk streaming generation loop:
                val chunks = listOf("Hello", "!", " This", " is", " a", " live", " stream", ".")
                
                for ((index, chunk) in chunks.withIndex()) {
                    val chunkMap = mapOf(
                        "id" to responseId,
                        "object" to "chat.completion.chunk",
                        "created" to System.currentTimeMillis() / 1000,
                        "model" to modelName,
                        "choices" to listOf(
                            mapOf(
                                "index" to 0,
                                "delta" to if (index == 0) mapOf("role" to "assistant", "content" to chunk) else mapOf("content" to chunk),
                                "finish_reason" to if (index == chunks.lastIndex) "stop" else null
                            )
                        )
                    )
                    
                    writer.write("data: ${gson.toJson(chunkMap)}\n\n")
                    writer.flush()
                    delay(50) // Simulating network or inference chunk timing
                }

                writer.write("data: [DONE]\n\n")
                writer.flush()
            } catch (e: Exception) {
                LogManager.e(TAG, "Streaming error occurred", e)
                writer.write("data: {\"error\": \"${e.message}\"}\n\n")
                writer.flush()
            }
        }

    }
    private fun buildContentsFromMessages(messages: List<Map<String, Any>>): List<Content> {
        val contents = mutableListOf<Content>()
        
        for (message in messages) {
            val role = message["role"] as? String ?: "user"
            val contentObj = message["content"]
            val parts = mutableListOf<Part>()

            when (contentObj) {
                is String -> {
                    parts.add(Part.text(contentObj))
                }
                is List<*> -> {
                    for (item in contentObj) {
                        val partMap = item as? Map<*, *> ?: continue
                        val type = partMap["type"] as? String
                        if (type == "text") {
                            val textValue = partMap["text"] as? String ?: ""
                            parts.add(Part.text(textValue))
                        } else if (type == "image_url") {
                            val imageUrlMap = partMap["image_url"] as? Map<*, *>
                            val urlStr = imageUrlMap?.get("url") as? String ?: ""
                            if (urlStr.startsWith("data:image")) {
                                try {
                                    val base64Data = urlStr.substringAfter("base64,")
                                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    // Replace with your specific custom image factory if Part context differs
                                    parts.add(Part.image(decodedBytes))
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "Failed parsing base64 image data", e)
                                }
                            }
                        }
                    }
                }
            }

            if (parts.isNotEmpty()) {
                // Adjust constructor configuration if your LiteRT-LM framework requires specific role objects
                contents.add(Content(role, parts))
            }
        }
        return contents
    }
    private fun handleCompletions(ctx: JavalinContext) {
        // Legacy textual /v1/completions fallback logic
        val bodyString = ctx.body()
        val requestMap = try {
            gson.fromJson<Map<String, Any>>(bodyString, object : TypeToken<Map<String, Any>>() {}.type)
        } catch (e: Exception) {
            ctx.status(400).json(mapOf("error" to "Invalid JSON format"))
            return
        }

        val prompt = requestMap["prompt"] as? String ?: ""
        val responseBody = mapOf(
            "id" to "cmpl-${System.currentTimeMillis()}",
            "object" to "text_completion",
            "created" to System.currentTimeMillis() / 1000,
            "model" to (requestMap["model"] as? String ?: "local-model"),
            "choices" to listOf(
                mapOf(
                    "text" to "Fallback legacy completion response to prompt: $prompt",
                    "index" to 0,
                    "logprobs" to null,
                    "finish_reason" to "stop"
                )
            )
        )
        ctx.contentType("application/json").result(gson.toJson(responseBody))
    }

    private fun handleModels(ctx: JavalinContext) {
        val modelsList = mapOf(
            "object" to "list",
            "data" to listOf(
                mapOf(
                    "id" to "local-model",
                    "object" to "model",
                    "created" to 1700000000,
                    "owned_by" to "hostai"
                )
            )
        )
        ctx.contentType("application/json").result(gson.toJson(modelsList))
   
    }
    private fun handleChatUi(ctx: JavalinContext) {
        val simpleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>HostAI Chat Interface</title>
                <meta charset="utf-8">
                <style>body { font-family: sans-serif; padding: 20px; }</style>
            </head>
            <body>
                <h1>HostAI API Server Running</h1>
                <p>Endpoint status: Active</p>
            </body>
            </html>
        """.trimIndent()
        ctx.contentType("text/html").result(simpleHtml)
    }

    private fun parseExtraBody(extraBodyObj: com.google.gson.JsonObject?): Map<String, Any>? {
        if (extraBodyObj == null || extraBodyObj.isEmpty()) {
            return null
        }
        
        LogManager.d(TAG, "Extra body provided in request with ${extraBodyObj.size()} properties")
        
        return extraBodyObj.entrySet().associate { entry ->
            val value: Any? = when {
                entry.value.isJsonPrimitive -> {
                    val primitive = entry.value.asJsonPrimitive
                    when {
                        primitive.isBoolean -> primitive.asBoolean
                        primitive.isNumber -> primitive.asDouble
                        primitive.isString -> primitive.asString
                        else -> primitive.asString
                    }
                }
                entry.value.isJsonNull -> null
                entry.value.isJsonArray -> entry.value.toString()
                entry.value.isJsonObject -> entry.value.toString()
                else -> entry.value.toString()
            }
            entry.key to value
        }.filterValues { it != null } as Map<String, Any>
    }

}
