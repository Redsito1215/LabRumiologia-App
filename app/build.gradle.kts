plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.uteq.software.labrumiologia"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.uteq.software.labrumiologia"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Nunca empaquetar claves de proveedores en el APK. La IA se consume vía backend.
        buildConfigField("String", "GEMINI_API_KEY", "\"\"")
        buildConfigField("String", "GEMINI_MODEL", "\"\"")
        buildConfigField("String", "RAG_BASE_URL", "\"${escapeJava(localValue("rag.base.url").ifEmpty { "http://10.0.2.2:8000/" })}\"")
        buildConfigField("String", "APP_ACCESS_TOKEN", "\"${escapeJava(localValue("assistant.app.token"))}\"")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.select.tf.ops)
    implementation(libs.gson)
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

val syncLabDocs = tasks.register<Copy>("syncLabDocs") {
    group = "lab"
    description = "Copia las guías del laboratorio a assets (el chat las usa en el teléfono)"
    from(rootProject.file("backend/data/docs")) {
        include("**/*.md", "**/*.txt")
    }
    into(file("src/main/assets/docs"))
}

val syncModel = tasks.register<Copy>("syncModel") {
    group = "lab"
    description = "Copia model.tflite (float32) entrenado a assets de la app"
    val candidates = listOf(
        rootProject.file("ml/models/model.tflite"),
        rootProject.file("ml/models/tflite_float32/best_float32.tflite"),
        rootProject.file("ml/models/best_saved_model/best_float32.tflite"),
    )
    val src = candidates.firstOrNull { it.exists() }
    onlyIf { src != null }
    from(src!!)
    into(file("src/main/assets"))
    rename { "model.tflite" }
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn(syncLabDocs, syncModel) }
}

fun escapeJava(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

fun localValue(key: String): String {
    val local = rootProject.file("local.properties")
    if (!local.exists()) return ""
    for (raw in local.readLines()) {
        val line = raw.trim()
        if (line.startsWith("$key=")) return line.substringAfter("=").trim().trim('"')
    }
    return ""
}
