import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.file.RegularFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.cyclonedx.bom)
}

// Release signing is fed exclusively from four environment variables; no key
// material or keystore path lives in the repository. No variable set: the
// release stays unsigned. Partially set, or a keystore inside the repository:
// the build aborts with a message that never contains a value (docs/release.md).
val releaseSigningVariables = listOf(
    "ORSIK_RELEASE_STORE_FILE",
    "ORSIK_RELEASE_STORE_PASSWORD",
    "ORSIK_RELEASE_KEY_ALIAS",
    "ORSIK_RELEASE_KEY_PASSWORD"
)
val releaseSigningValues = releaseSigningVariables.map { name ->
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
}

android {
    namespace = "io.github.excludebarrier.orsikrelay"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.excludebarrier.orsikrelay"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.0-alpha.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildToolsVersion = "36.1.0"

    signingConfigs {
        if (releaseSigningValues.any { it != null }) {
            if (releaseSigningValues.any { it == null }) {
                throw GradleException(
                    "Release signing needs all of " + releaseSigningVariables.joinToString(", ")
                )
            }
            val keystoreFile = file(releaseSigningValues[0]!!)
            if (keystoreFile.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath())) {
                throw GradleException("Release keystore must live outside the repository")
            }
            if (!keystoreFile.exists()) {
                throw GradleException("Release keystore file does not exist")
            }
            create("release") {
                storeFile = keystoreFile
                storePassword = releaseSigningValues[1]
                keyAlias = releaseSigningValues[2]
                keyPassword = releaseSigningValues[3]
            }
        }
    }

    buildTypes {
        debug {
            // Report-only JaCoCo coverage for JVM unit tests (AGP built-in);
            // no coverage threshold gate. Instrumentation coverage stays off.
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningValues.all { it != null }) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// CycloneDX SBOM of the release runtime classpath (docs/release.md).
// JSON only: app/build/reports/sbom/app.cdx.json.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    projectType = Component.Type.APPLICATION
    includeConfigs = listOf("releaseRuntimeClasspath")
    jsonOutput = layout.buildDirectory.file("reports/sbom/app.cdx.json")
    xmlOutput.convention(null as RegularFile?)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // Markdown rendering for assistant messages (P2). Pure Compose, no WebView.
    implementation(libs.multiplatform.markdown.renderer)
    implementation(libs.multiplatform.markdown.renderer.m3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.json)
    // Deterministic (virtual-time) tests for the debounced chat-draft
    // repository (Mission 009A). Test-only; no production dependency.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
