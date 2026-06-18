plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wannaphong.hostai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wannaphong.hostai"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.1.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11    
    }
    
    // Modern Kotlin compiler configuration (replaces deprecated jvmTarget)
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            // Tells Kotlin 2.1.0 to safely ignore the Kotlin 2.3.0 metadata version warning
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
    
    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            // These exclusions prevent duplicate file errors when merging Jetty/Javalin
            excludes.add("org/eclipse/jetty/http/encoding.properties")
            excludes.add("META-INF/maven/org.eclipse.jetty/jetty-server/pom.properties")
            excludes.add("about.html")
        }
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.12.0")
        force("androidx.core:core-ktx:1.12.0")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Jetty suite for HTTP server
    implementation("org.eclipse.jetty:jetty-server:9.4.48.v20220622")
    implementation("org.eclipse.jetty:jetty-util:9.4.48.v20220622")
    implementation("org.eclipse.jetty:jetty-http:9.4.48.v20220622")
    implementation("org.eclipse.jetty:jetty-io:9.4.48.v20220622")
    implementation("org.eclipse.jetty:jetty-servlet:9.4.48.v20220622")
    
    // Javalin and supporting libs
    implementation("io.javalin:javalin:4.6.8")
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Core Android libs
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // LiteRT
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.1") 
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
