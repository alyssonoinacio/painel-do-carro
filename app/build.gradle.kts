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
        targetSdk = 34
        versionCode = 3
        versionName = "2.0"
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
