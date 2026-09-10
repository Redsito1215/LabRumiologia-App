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
    description = "Copia las guías del laboratorio a los recursos de la aplicación"
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
