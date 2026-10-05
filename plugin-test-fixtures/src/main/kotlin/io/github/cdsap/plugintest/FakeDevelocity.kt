package io.github.cdsap.plugintest

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Entry points for the offline fake Develocity ([FakeDevelocityPlugin]) and Gradle Enterprise
 * ([FakeGradleEnterprisePlugin]) plugins.
 *
 * The fakes implement the real Develocity API interfaces, so the TestKit build needs them on the same classpath as
 * the fake. Add the Develocity plugin to the test classpath, for example
 * `testImplementation("com.gradle:develocity-gradle-plugin:3.19.2")` (the last line that also ships the legacy Gradle
 * Enterprise API), and pass [develocityApiJar] to one of:
 *
 * - Plugins resolved from a Maven repository: [SettingsScript.fakeDevelocity] maps the plugin ids
 *   `com.gradle.develocity` and `com.gradle.enterprise` to `io.github.cdsap:plugin-test-fixtures` with a
 *   `pluginManagement.resolutionStrategy` rule and puts the API jar on the settings classpath; then request the
 *   plugin with `plugin(FakeDevelocity.PLUGIN_ID)`.
 * - `GradleRunner.withPluginClasspath`: put this library's jar and [develocityApiJar] on the injected classpath.
 * - Develocity injected by an init script, whose classes the plugins under test cannot see: [writeInitScript].
 *
 * The fake's plugin descriptors use the real ids because Gradle looks a plugin up under the requested id in the
 * module a resolution rule selects. [develocityApiJar] strips the real plugin's descriptors, so the fake is the only
 * `com.gradle.develocity` and `com.gradle.enterprise` plugin on the classpath whatever the classpath order.
 *
 * The fake extensions are `java.lang.reflect.Proxy` instances, not Gradle-decorated objects, so a Groovy closure
 * passed to one of their `Action` methods does not delegate to the configured object. Use the closure parameter in
 * build scripts, for example `develocity.buildScan { it.value('k', 'v') }`.
 */
public object FakeDevelocity {
    /** Id of the Develocity plugin, under which [FakeDevelocityPlugin] is applied. */
    public const val PLUGIN_ID: String = "com.gradle.develocity"

    /** Id of the legacy Gradle Enterprise plugin, under which [FakeGradleEnterprisePlugin] is applied. */
    public const val LEGACY_PLUGIN_ID: String = "com.gradle.enterprise"

    /** Printed by [FakeDevelocityPlugin] when it is applied. */
    public const val APPLIED_MARKER: String = "FAKE-DEVELOCITY applied"

    /** Printed by [FakeGradleEnterprisePlugin] when it is applied. */
    public const val LEGACY_APPLIED_MARKER: String = "FAKE-GRADLE-ENTERPRISE applied"

    /** Prefix of the line printed for each Build Scan custom value: `SCAN-VALUE <name>=<value>`. */
    public const val SCAN_VALUE_PREFIX: String = "SCAN-VALUE "

    /** Prefix of the line printed for each Build Scan tag: `SCAN-TAG <tag>`. */
    public const val SCAN_TAG_PREFIX: String = "SCAN-TAG "

    private const val DEVELOCITY_CONFIGURATION = "com.gradle.develocity.agent.gradle.DevelocityConfiguration"
    private const val PLUGIN_DESCRIPTORS = "META-INF/gradle-plugins/"

    /** The `<name>=<value>` custom values the fakes printed in [output], in order. */
    public fun scanValues(output: String): List<String> = linesWithPrefix(output, SCAN_VALUE_PREFIX)

    /** The tags the fakes printed in [output], in order. */
    public fun scanTags(output: String): List<String> = linesWithPrefix(output, SCAN_TAG_PREFIX)

    /**
     * A copy of the Develocity plugin jar found on this JVM's classpath, without its plugin descriptors and jar
     * signatures, written to [directory] (created when missing, reused when already written). Put it next to the fake:
     * it provides the API interfaces the fakes implement but can never be applied as the real plugin.
     *
     * @throws IllegalStateException when no Develocity plugin jar is on the classpath.
     */
    public fun develocityApiJar(directory: File): File {
        val source = develocityPluginJar()
        val target = File(directory, "${source.nameWithoutExtension}-${source.length()}-${source.lastModified()}-api.jar")
        if (target.isFile) return target
        directory.mkdirs()
        val temporary = File.createTempFile(target.name, ".tmp", directory)
        try {
            ZipInputStream(source.inputStream().buffered()).use { input ->
                ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                    val written = HashSet<String>()
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (keepApiEntry(entry.name) && written.add(entry.name)) {
                            output.putNextEntry(ZipEntry(entry.name).also { it.time = entry.time })
                            input.copyTo(output)
                            output.closeEntry()
                        }
                    }
                }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        return target
    }

    /**
     * Writes a Groovy init script to [initScript] that applies [FakeDevelocityPlugin] to the settings from
     * `beforeSettings`, the way Develocity is injected by CI init scripts. The fake and [develocityApiJar] are loaded
     * by the init script's classloader, so the plugins under test cannot see the Develocity types. Pass the script with
     * `--init-script <path>`.
     */
    public fun writeInitScript(initScript: File, develocityApiJar: File): File {
        val classpath = listOf(codeSource(FakeDevelocityPlugin::class.java), develocityApiJar)
        initScript.parentFile?.mkdirs()
        initScript.writeText(
            """
            |initscript {
            |    dependencies {
            |        classpath files(${classpath.joinToString(", ") { groovyString(it.absolutePath) }})
            |    }
            |}
            |beforeSettings { settings ->
            |    settings.pluginManager.apply(${FakeDevelocityPlugin::class.java.name})
            |}
            |
            """.trimMargin(),
        )
        return initScript
    }

    private fun linesWithPrefix(output: String, prefix: String): List<String> =
        output.lineSequence().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toList()

    private fun develocityPluginJar(): File {
        val type =
            try {
                Class.forName(DEVELOCITY_CONFIGURATION, false, FakeDevelocity::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                throw IllegalStateException(
                    "The Develocity plugin is not on the test classpath. Add it, for example " +
                        "testImplementation(\"com.gradle:develocity-gradle-plugin:3.19.2\").",
                )
            }
        val jar = codeSource(type)
        check(jar.isFile) { "Expected $DEVELOCITY_CONFIGURATION to be loaded from a jar, not $jar" }
        return jar
    }

    private fun keepApiEntry(name: String): Boolean {
        if (name.startsWith(PLUGIN_DESCRIPTORS)) return false
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length) >= 0) return true
        val extension = name.substringAfterLast('.', "").uppercase()
        return extension !in setOf("SF", "RSA", "DSA", "EC")
    }
}

internal fun codeSource(type: Class<*>): File = File(type.protectionDomain.codeSource.location.toURI())
