plugins {
    id("com.android.application")
}

android {
    namespace = "com.kivan.motoparty.spike"
    // Only platform android-37.2 is installed; 37 + minor 2 selects it (same as ../../android).
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.kivan.motoparty.spike"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No dependencies on purpose: plain framework Views, plain framework audio/speech APIs.
dependencies {
}
