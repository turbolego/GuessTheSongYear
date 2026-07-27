import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.turbolego.songguesser"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.turbolego.songguesser"
        minSdk = 24
        targetSdk = 37
        versionCode = 98
        versionName = "1.0.98"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Force safe versions of vulnerable transitive dependencies where needed
// This handles build-time configurations (lint, etc.) that the constraints block doesn't reach.
// Only applies when the resolved version is below the safe threshold.
configurations.all {
    resolutionStrategy {
        eachDependency {
            val requested = requested
            when {
                // Netty — upgrade to latest 4.1.x with all security fixes
                requested.group == "io.netty" && requested.version?.let { v ->
                    v.startsWith("4.") && v < "4.1.136.Final"
                } == true -> useVersion("4.1.136.Final")

                // Bouncy Castle — critical CVE-2025-14813 fixed in 1.80.2, then 1.84
                requested.group == "org.bouncycastle" && requested.version?.let { v ->
                    v < "1.84"
                } == true -> useVersion("1.84")

                // Apache HttpClient — CVE-2020-13956 fixed in 4.5.13
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" &&
                    requested.version?.let { v -> v < "4.5.14" } == true -> useVersion("4.5.14")

                // Apache Commons Lang — CVE-2025-48924 fixed in 3.18.0
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" &&
                    requested.version?.let { v -> v < "3.18.0" } == true -> useVersion("3.18.0")

                // Apache Commons Compress — CVEs fixed in 1.26.0+, use 1.28.0
                requested.group == "org.apache.commons" && requested.name == "commons-compress" &&
                    requested.version?.let { v -> v < "1.28.0" } == true -> useVersion("1.28.0")

                // jose4j — CVE-2024-29371 fixed in 0.9.6
                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" &&
                    requested.version?.let { v -> v < "0.9.6" } == true -> useVersion("0.9.6")

                // JDOM2 — CVE-2021-33813 fixed in 2.0.6.1
                requested.group == "org.jdom" && requested.name == "jdom2" &&
                    requested.version?.let { v -> v < "2.0.6.1" } == true -> useVersion("2.0.6.1")

                // Protobuf — CVE-2024-7254 fixed in 4.28.0+
                requested.group == "com.google.protobuf" &&
                    requested.version?.let { v -> v < "4.28.3" } == true -> useVersion("4.28.3")
            }
        }
    }
}

dependencies {
    // Core library
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // QR Code generation + scanning
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // YouTube video playback (official IFrame Player API — no API key needed)
    implementation(libs.android.youtube.player)

    // InnerTube API search
    implementation(libs.okhttp)

    // Bouncy Castle for TLS certificate generation (SecureChannelManager)
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.bouncycastle:bcutil-jdk18on:1.85")

    // WiFi P2P for local multiplayer (using built-in Android APIs)

    // Parcelize is included in the kotlin-android plugin

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.android)
    // Real org.json for JVM unit tests (Android mock throws on every method call)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Enforce safe versions of transitive dependencies
    constraints {
        implementation("io.netty:netty-codec-http:4.2.16.Final") {
            because("CVE-2026-59921, CVE-2026-59899, CVE-2026-59898, CVE-2026-56746, CVE-2026-56745, CVE-2026-55833, CVE-2026-55831, CVE-2026-50020, CVE-2026-42585, CVE-2026-42584, CVE-2026-42581, CVE-2026-42580, CVE-2026-41417, CVE-2026-33870, CVE-2025-67735, CVE-2025-58056, CVE-2024-29025")
        }
        implementation("io.netty:netty-codec-http2:4.2.16.Final") {
            because("CVE-2026-59900, CVE-2026-50560, CVE-2026-48043, CVE-2026-47244, CVE-2026-42587, CVE-2026-33871, CVE-2025-55163")
        }
        implementation("io.netty:netty-handler:4.2.16.Final") {
            because("CVE-2026-50010, CVE-2026-45416, CVE-2026-44249, CVE-2025-24970, CVE-2023-34462")
        }
        implementation("io.netty:netty-codec:4.2.16.Final") {
            because("CVE-2026-59901, CVE-2026-42583, CVE-2025-58057")
        }
        implementation("io.netty:netty-handler-proxy:4.2.16.Final") {
            because("CVE-2026-42578")
        }
        implementation("io.netty:netty-common:4.2.16.Final") {
            because("CVE-2025-25193, CVE-2024-47535")
        }
        implementation("io.netty:netty-buffer:4.2.16.Final") { because("Netty bom alignment") }
        implementation("io.netty:netty-transport:4.2.16.Final") { because("Netty bom alignment") }
        implementation("io.netty:netty-resolver:4.2.16.Final") { because("Netty bom alignment") }

        implementation("org.bouncycastle:bcprov-jdk18on:1.85") {
            because("CVE-2025-14813 (CRITICAL), GHSA-c3fc-8qff-9hwx")
        }
        implementation("org.bouncycastle:bcpkix-jdk18on:1.85") {
            because("GHSA-wg6q-6289-32hp")
        }
        implementation("org.bouncycastle:bcutil-jdk18on:1.85") {
            because("BC bom alignment")
        }

        implementation("org.apache.httpcomponents:httpclient:4.5.14") {
            because("CVE-2020-13956")
        }
        implementation("org.apache.commons:commons-lang3:3.20.0") {
            because("CVE-2025-48924")
        }
        implementation("org.apache.commons:commons-compress:1.28.0") {
            because("CVE-2024-25710, CVE-2024-26308")
        }
        implementation("org.bitbucket.b_c:jose4j:0.9.6") {
            because("CVE-2024-29371")
        }
        implementation("org.jdom:jdom2:2.0.6.1") {
            because("CVE-2021-33813")
        }
        implementation("com.google.android.gms:play-services-basement:18.10.0") {
            because("CVE-2022-2390")
        }
        implementation("com.google.android.gms:play-services-tasks:18.4.1") {
            because("CVE-2022-2390 transitive")
        }
        implementation("com.google.guava:guava:33.6.0-jre") {
            because("CVE-2020-8908, CVE-2023-2976")
        }
        implementation("com.google.protobuf:protobuf-java:4.35.1") {
            because("CVE-2024-7254")
        }
        implementation("com.google.protobuf:protobuf-kotlin:4.35.1") {
            because("CVE-2024-7254")
        }
    }
}
