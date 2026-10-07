package io.github.cdsap.plugintest

import java.io.File

/**
 * Builds a Groovy DSL `settings.gradle` for a TestKit build, in the block order Gradle requires
 * (`pluginManagement`, `buildscript`, `plugins`, then the rest).
 *
 * ```
 * SettingsScript()
 *     .localMavenRepository(repo)
 *     .fakeDevelocity(fixturesVersion = "0.1.0", develocityApiJar = FakeDevelocity.develocityApiJar(workDir))
 *     .plugin(FakeDevelocity.PLUGIN_ID)
 *     .plugin("io.github.cdsap.gcreport", "1.0.0")
 *     .rootProjectName("consumer")
 *     .writeTo(projectDir)
 * ```
 */
public class SettingsScript {
    private val repositories = mutableListOf<File>()
    private var fakeDevelocityVersion: String? = null
    private val classpath = mutableListOf<String>()
    private val plugins = mutableListOf<String>()
    private var rootProjectName: String? = null
    private val includes = mutableListOf<String>()
    private val body = mutableListOf<String>()

    /**
     * Local-Maven-repository convention: [directory] (a Maven layout, for example a `maven-publish` file repository)
     * is searched first, then the Plugin Portal and Maven Central, for plugin requests (`pluginManagement`), the
     * settings `buildscript` classpath and project dependencies (`dependencyResolutionManagement`).
     */
    public fun localMavenRepository(directory: File): SettingsScript = apply { repositories += directory }

    /**
     * Resolves the plugin ids [FakeDevelocity.PLUGIN_ID] and [FakeDevelocity.LEGACY_PLUGIN_ID] to
     * `io.github.cdsap:plugin-test-fixtures:<fixturesVersion>` (from the repositories of [localMavenRepository]) and
     * puts [develocityApiJar] on the settings classpath. Request the fake with [plugin] as usual.
     */
    public fun fakeDevelocity(fixturesVersion: String, develocityApiJar: File): SettingsScript =
        apply {
            fakeDevelocityVersion = fixturesVersion
            classpath += "files(${groovyString(develocityApiJar.absolutePath)})"
        }

    /** Adds a module, such as `io.github.cdsap:plugin-support:2.0.0`, to the settings `buildscript` classpath. */
    public fun buildscriptClasspath(notation: String): SettingsScript = apply { classpath += groovyString(notation) }

    /** Requests the settings plugin [id], at [version] when given. */
    public fun plugin(id: String, version: String? = null): SettingsScript = apply { plugins += pluginRequest(id, version) }

    /** Sets `rootProject.name`. */
    public fun rootProjectName(name: String): SettingsScript = apply { rootProjectName = name }

    /** Includes the projects at [paths], for example `"sub"`. */
    public fun include(vararg paths: String): SettingsScript = apply { includes += paths }

    /** Appends Groovy [code] after the generated blocks. */
    public fun append(code: String): SettingsScript = apply { body += code }

    /** The script text. */
    public fun render(): String =
        buildString {
            val fakeVersion = fakeDevelocityVersion
            if (repositories.isNotEmpty() || fakeVersion != null) {
                appendLine("pluginManagement {")
                appendRepositories(repositories, indent = "    ", pluginPortal = true)
                if (fakeVersion != null) {
                    appendLine("    resolutionStrategy {")
                    appendLine("        eachPlugin {")
                    appendLine(
                        "            if (requested.id.id in [${groovyString(FakeDevelocity.PLUGIN_ID)}, " +
                            "${groovyString(FakeDevelocity.LEGACY_PLUGIN_ID)}]) {",
                    )
                    appendLine("                useModule(${groovyString("io.github.cdsap:plugin-test-fixtures:$fakeVersion")})")
                    appendLine("            }")
                    appendLine("        }")
                    appendLine("    }")
                }
                appendLine("}")
            }
            appendBuildscript(repositories, classpath)
            appendPlugins(plugins)
            if (repositories.isNotEmpty()) {
                appendLine("dependencyResolutionManagement {")
                appendRepositories(repositories, indent = "    ", pluginPortal = false)
                appendLine("}")
            }
            rootProjectName?.let { appendLine("rootProject.name = ${groovyString(it)}") }
            includes.forEach { appendLine("include ${groovyString(it)}") }
            body.forEach { appendLine(it) }
        }

    /** Writes [render] to `settings.gradle` in [projectDir] and returns the file. */
    public fun writeTo(projectDir: File): File = writeScript(projectDir, "settings.gradle", render())
}

/**
 * Builds a Groovy DSL `build.gradle` for a TestKit build.
 *
 * ```
 * BuildScript()
 *     .localMavenRepository(repo)
 *     .buildscriptClasspath("io.github.cdsap:plugin-support:2.0.0")
 *     .plugin("io.github.cdsap.gcreport", "1.0.0")
 *     .writeTo(projectDir)
 * ```
 *
 * Build script `plugins {}` requests resolve from the settings `pluginManagement` repositories; [localMavenRepository]
 * is only needed for [buildscriptClasspath] entries.
 */
public class BuildScript {
    private val repositories = mutableListOf<File>()
    private val classpath = mutableListOf<String>()
    private val plugins = mutableListOf<String>()
    private val body = mutableListOf<String>()

    /** Searches [directory] first, then the Plugin Portal and Maven Central, for the `buildscript` classpath. */
    public fun localMavenRepository(directory: File): BuildScript = apply { repositories += directory }

    /** Adds a module to the `buildscript` classpath, next to the `plugins {}` requests of this script. */
    public fun buildscriptClasspath(notation: String): BuildScript = apply { classpath += groovyString(notation) }

    /** Requests the plugin [id], at [version] when given. */
    public fun plugin(id: String, version: String? = null): BuildScript = apply { plugins += pluginRequest(id, version) }

    /** Appends Groovy [code] after the generated blocks. */
    public fun append(code: String): BuildScript = apply { body += code }

    /** The script text. */
    public fun render(): String =
        buildString {
            appendBuildscript(repositories, classpath)
            appendPlugins(plugins)
            body.forEach { appendLine(it) }
        }

    /** Writes [render] to `build.gradle` in [projectDir] and returns the file. */
    public fun writeTo(projectDir: File): File = writeScript(projectDir, "build.gradle", render())
}

private fun StringBuilder.appendRepositories(repositories: List<File>, indent: String, pluginPortal: Boolean) {
    appendLine("${indent}repositories {")
    repositories.forEach { appendLine("$indent    maven { url = uri(${groovyString(it.absolutePath)}) }") }
    if (pluginPortal) appendLine("$indent    gradlePluginPortal()")
    appendLine("$indent    mavenCentral()")
    appendLine("$indent}")
}

private fun StringBuilder.appendBuildscript(repositories: List<File>, classpath: List<String>) {
    if (classpath.isEmpty()) return
    appendLine("buildscript {")
    if (repositories.isNotEmpty()) appendRepositories(repositories, indent = "    ", pluginPortal = true)
    appendLine("    dependencies {")
    classpath.forEach { appendLine("        classpath $it") }
    appendLine("    }")
    appendLine("}")
}

private fun StringBuilder.appendPlugins(plugins: List<String>) {
    if (plugins.isEmpty()) return
    appendLine("plugins {")
    plugins.forEach { appendLine("    $it") }
    appendLine("}")
}

private fun pluginRequest(id: String, version: String?): String =
    if (version == null) "id ${groovyString(id)}" else "id ${groovyString(id)} version ${groovyString(version)}"

private fun writeScript(projectDir: File, name: String, text: String): File {
    projectDir.mkdirs()
    return File(projectDir, name).also { it.writeText(text) }
}

internal fun groovyString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
