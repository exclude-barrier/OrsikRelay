// Root build script: declares plugins for the single app module.
//
// AGP 9.x provides built-in Kotlin support and pins Kotlin Gradle Plugin (KGP)
// 2.2.10 on the build classpath. That version is affected by
// CVE-2026-53914 (GHSA-r937-wjx7-w2jp, fixed in 2.4.20), so the same KGP
// version used by the Compose compiler plugin (gradle/libs.versions.toml
// `kotlin`) is pinned here explicitly; both must stay in lockstep.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
