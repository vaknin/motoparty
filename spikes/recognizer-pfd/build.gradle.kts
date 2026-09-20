// AGP 9 has Kotlin built in (do not apply `kotlin-android`); the Kotlin Gradle plugin on the
// buildscript classpath selects the Kotlin version. Versions copied from
// ../../android/gradle/libs.versions.toml (agp 9.4.0, kotlin 2.4.10) so this builds with the
// toolchain already installed on this machine.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
