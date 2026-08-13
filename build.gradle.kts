import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.claudecode"
// Overridable from CI: ./gradlew buildPlugin -PpluginVersion=0.2.0
version = property("pluginVersion") as String

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

/**
 * The topmost section of CHANGELOG.md, as the HTML the plugin descriptor wants.
 *
 * Deliberately a small parser rather than a markdown library: it only has to
 * read what this project writes, and a build that pulls in a dependency to
 * format its own release notes is worse than the six lines below. Each bullet
 * is expected on a single line — CHANGELOG.md says so.
 */
fun latestChangeNotes(): String {
    val changelog = file("CHANGELOG.md")
    if (!changelog.exists()) return ""

    val lines = changelog.readLines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) return ""
    val rest = lines.drop(start + 1)
    val end = rest.indexOfFirst { it.startsWith("## ") }.let { if (it < 0) lines.size else start + 1 + it }

    val items = lines.subList(start + 1, end)
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { inlineHtml(it.removePrefix("- ").trim()) }

    return if (items.isEmpty()) "" else items.joinToString("", "<ul>", "</ul>") { "<li>$it</li>" }
}

/** Escapes the text, then restores the little markup the notes actually use. */
fun inlineHtml(markdown: String): String = markdown
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
    .replace(Regex("""`(.+?)`"""), "<code>$1</code>")

tasks {
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    test {
        // CI runners have no display; run tests the same way locally so a headless
        // failure can't hide until it reaches CI.
        systemProperty("java.awt.headless", "true")
        // Gradle sets -D on its own JVM, not the test JVM; forward the opt-in flag
        // used by the statistics preview harness.
        System.getProperty("claudebrains.preview")?.let { systemProperty("claudebrains.preview", it) }
    }

    patchPluginXml {
        // Release notes come from CHANGELOG.md rather than living in plugin.xml,
        // where nineteen releases of them had grown to two thirds of the file
        // and had to be hand-edited in HTML for every release.
        changeNotes.set(provider { latestChangeNotes() })

        sinceBuild.set("241")
        // No upper bound: an explicit untilBuild makes the IDE refuse to install
        // the plugin on any newer release (2026.1 is build 261, so "252.*" locked
        // it out). Only stable platform APIs are used, so let it load anywhere
        // from 2024.1 up and fix things if a future release actually breaks it.
        untilBuild.set(provider { null })
    }

    // This is what would have caught the 2026.2 breakage before a user did: the
    // embedded browser moved out of the platform, and every call into it became
    // a NoClassDefFoundError. With untilBuild deliberately open-ended, the
    // verifier is the only thing standing between "installs anywhere" and
    // "installs anywhere and dies on launch".
    //
    // Bump the target list in gradle.properties; CI runs this as its own job
    // because each IDE it checks against is a large download.
    runPluginVerifier {
        ideVersions.set(
            providers.gradleProperty("pluginVerifierIdeVersions")
                .map { list -> list.split(",").map(String::trim).filter(String::isNotEmpty) }
        )
        // A plugin that tracks a moving platform always has deprecation warnings;
        // a real incompatibility is a different thing and should stop the build.
        failureLevel.set(
            listOf(
                org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel.INVALID_PLUGIN
            )
        )
    }

    signPlugin {
        enabled = false
    }

    publishPlugin {
        enabled = false
    }
}
