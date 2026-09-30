import java.util.Properties

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

// Release signing (H5). The repository is public, so neither the keystore nor its passwords are in
// it: they come from android/keystore.properties (git-ignored) or, failing that, from
// ~/.gradle/gradle.properties (`motoparty.storeFile`, `.storePassword`, `.keyAlias`,
// `.keyPassword`). Without them `assembleRelease` still builds, unsigned; `assembleDebug` never
// looks at any of this. To create both files once (run in android/; keep a copy of the two files
// somewhere safe — an update can only be installed over a build signed with the same key):
//
//   P=$(openssl rand -hex 16) && keytool -genkeypair -keystore motoparty-release.jks \
//     -alias motoparty -keyalg RSA -keysize 4096 -validity 10000 -storepass "$P" -keypass "$P" \
//     -dname "CN=Motoparty" && printf 'storeFile=motoparty-release.jks\nstorePassword=%s\nkeyAlias=motoparty\nkeyPassword=%s\n' \
//     "$P" "$P" > keystore.properties && chmod 600 keystore.properties motoparty-release.jks
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}
fun signingValue(name: String): String? =
    keystoreProperties.getProperty(name) ?: providers.gradleProperty("motoparty.$name").orNull
// The Gemini API key of the smart commands (PROTOCOL.md "Commands", Interpretation), from the
// git-ignored local.properties. Without one the build works and the feature is off.
val geminiApiKey: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use(::load)
}.getProperty("gemini.apiKey", "").trim()
val releaseStoreFile = signingValue("storeFile")?.let { rootProject.file(it) }?.takeIf { it.isFile }

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
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
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

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 in full mode: shrinks and optimises (the debuggable, unshrunk Compose of the debug
            // build is visibly less smooth). Names are kept, see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
        buildConfig = true
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
        // Robolectric (screenshot tests) needs the merged resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// The shared protocol vectors live at the repo root, next to PROTOCOL.md.
val fixturesDir = rootProject.file("../fixtures").absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("motoparty.fixtures", fixturesDir)
    // The vectors are test inputs: an edited fixture must re-run the tests, not hit the up-to-date check.
    inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    // Network tests (YouTube extraction) run only when asked: ./gradlew test -Pnetwork
    systemProperty("motoparty.network", providers.gradleProperty("network").isPresent.toString())
    // Screenshot tests write PNGs to app/build/outputs/roborazzi only when asked: -Pscreenshots
    systemProperty("roborazzi.test.record", providers.gradleProperty("screenshots").isPresent.toString())
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
    implementation(libs.media3.muxer)
    implementation(libs.media3.extractor)
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.palette.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.coil.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
