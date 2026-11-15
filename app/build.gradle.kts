plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "br.car.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "br.car.launcher"
        minSdk = 21
        targetSdk = 32 // 32: no Android 13 o app ainda pode ligar/desligar o Bluetooth por voz
        versionCode = 7
        versionName = "2.4"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = false; checkReleaseBuilds = false }
    kotlinOptions { jvmTarget = "17" }
}
