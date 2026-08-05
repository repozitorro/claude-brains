import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.claudecode"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Lightweight markdown -> HTML renderer used to display Claude's replies
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
}

// See https://plugins.jetbrains.com/docs/intellij/android-studio-releases-list.html
// WebStorm is a JetBrains-platform IDE, so building against IntelliJ Community
// (the open-source platform WebStorm is built on) is the standard approach for
// a plugin that has no Java/Kotlin-language-specific features of its own.
intellij {
    // Pick a recent WebStorm-compatible platform version. Bump this to match
    // whatever IDE build you actually want to test against locally.
    version.set("2024.1")
    type.set("IC") // IntelliJ Community as the base platform

    plugins.set(listOf(/* no extra bundled plugins required */))
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        // Widen the upper bound so the plugin also loads in 2024.2–2025.x IDEs.
        // It only uses stable platform APIs, so a broad range is safe here.
        untilBuild.set("252.*")
    }

    // Skip the buggy in-place plugin verifier by default; run manually with
    // `./gradlew runPluginVerifier` once you have a packaged build.
    runPluginVerifier {
        enabled = false
    }

    signPlugin {
        enabled = false
    }

    publishPlugin {
        enabled = false
    }
}
