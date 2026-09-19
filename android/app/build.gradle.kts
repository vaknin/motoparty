plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// libopus is not committed; tools/fetch_opus.sh downloads and verifies the release tarball.
val opusSource = file("src/main/cpp/opus/CMakeLists.txt")
check(opusSource.exists()) {
    "Missing ${opusSource.relativeTo(rootDir)}. Run tools/fetch_opus.sh first (see README.md)."
}

android {
    namespace = "com.kivan.motoparty"
    // Only platform android-37.2 is installed; 37 + minor 2 selects it (same as ~/Work/Pronounce).
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.kivan.motoparty"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // The Pixel 8 is arm64-v8a; nothing else is built or shipped.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-O2")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor targets plain JVM APIs; desugaring keeps them working on older ART.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The shared protocol vectors live at the repo root, next to PROTOCOL.md.
val fixturesDir = rootProject.file("../fixtures").absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("motoparty.fixtures", fixturesDir)
    // Network tests (YouTube extraction) run only when asked: ./gradlew test -Pnetwork
    systemProperty("motoparty.network", providers.gradleProperty("network").isPresent.toString())
    testLogging {
        events("failed", "skipped")
        showStandardStreams = providers.gradleProperty("network").isPresent
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
