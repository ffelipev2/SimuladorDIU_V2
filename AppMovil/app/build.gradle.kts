plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.felipe.endoscopeviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.felipe.endoscopeviewer"
        minSdk = 23
        targetSdk = 35
        versionCode = 20
        versionName = "2.9"
    }

    // Mantiene Java y Kotlin en el mismo objetivo, aunque Android Studio use JDK 21.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    val cameraX = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.github.chenyeju295.AndroidUSBCamera:libausbc:3.3.6")
}
