// AGP 9 has Kotlin built in (do not apply `kotlin-android`); the Kotlin Gradle plugin on the
// buildscript classpath selects the Kotlin version, as in ~/Work/Pronounce.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
