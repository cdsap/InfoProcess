package io.github.cdsap.pluginsupport

import com.gradle.develocity.agent.gradle.DevelocityConfiguration
import io.github.cdsap.pluginsupport.fake.FakeDevelocityPlugin
import io.github.cdsap.pluginsupport.fake.FakeGradleEnterprisePlugin
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Gradle8_2FixturePluginTest : FixturePluginTestKitTest("8.2")

class Gradle9_7_1FixturePluginTest : FixturePluginTestKitTest("9.7.1")

/**
 * Drives the test-only fixture plugin (`fixture/FixturePlugin.kt`, built on plugin-support) through TestKit. On
 * Gradle 8.2 the library runs against Gradle's embedded Kotlin 1.8.20 standard library, so that lane proves R6.
 * The fake Develocity plugins print `SCAN-VALUE` / `SCAN-TAG` lines from their `buildFinished` replay.
 */
abstract class FixturePluginTestKitTest(private val gradleVersion: String) {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `develocity applied before the plugin registers the reporter once and values reach the scan`() {
        writeProject(settingsPlugins = listOf(DEVELOCITY, FIXTURE))

        val result = run()

        assertTrue(result.output.contains(FakeDevelocityPlugin.APPLIED_MARKER), result.output)
        assertConfiguredOnce(result)
        assertEquals(listOf("FIXTURE reporter registered access=DEVELOCITY"), reporterLines(result))
        assertEquals(scanLines("DEVELOCITY"), scanLines(result))
        assertEquals(emptyList(), consoleLines(result), "console rule defaults to off with Develocity")
    }

    @Test
    fun `develocity applied after the plugin gives the same outcome`() {
        writeProject(settingsPlugins = listOf(FIXTURE, DEVELOCITY))

        val result = run()

        assertConfiguredOnce(result)
        assertEquals(listOf("FIXTURE reporter registered access=DEVELOCITY"), reporterLines(result))
        assertEquals(scanLines("DEVELOCITY"), scanLines(result))
        assertEquals(emptyList(), consoleLines(result))
    }

    @Test
    fun `without develocity only the console hook runs`() {
        writeProject(settingsPlugins = listOf(FIXTURE))

        val result = run()
        assertConfiguredOnce(result)
        assertEquals(emptyList(), reporterLines(result))
        assertEquals(emptyList(), scanLines(result))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(result))
    }

    @Test
    fun `plugin console rule applies with develocity and the DSL wins over the property`() {
        writeProject(settingsPlugins = listOf(DEVELOCITY, FIXTURE), properties = mapOf("fixture.consoleWithDevelocity" to "true"))
        val byProperty = run()
        assertEquals(scanLines("DEVELOCITY"), scanLines(byProperty))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(byProperty))

        writeProject(
            settingsPlugins = listOf(DEVELOCITY, FIXTURE),
            properties = mapOf("fixture.consoleWithDevelocity" to "true"),
            settingsBody = "fixtureReport { consoleWithDevelocity = false }",
        )
        val dslOff = run()
        assertEquals(scanLines("DEVELOCITY"), scanLines(dslOff))
        assertEquals(emptyList(), consoleLines(dslOff))
    }

    @Test
    fun `develocity injected by an init script reaches the scan and keeps the console`() {
        writeProject(settingsPlugins = listOf(FIXTURE))
        val initScript = File(projectDir, "inject-develocity.gradle")
        initScript.writeText(
            """
            initscript {
                dependencies {
                    classpath files(${initScriptClasspath().joinToString(", ") { "'${it.invariantSeparatorsPath}'" }})
                }
            }
            beforeSettings { settings ->
                settings.pluginManager.apply(io.github.cdsap.pluginsupport.fake.FakeDevelocityPlugin)
            }
            """.trimIndent(),
        )

        val result = run("--init-script", initScript.absolutePath)

        assertTrue(result.output.contains(FakeDevelocityPlugin.APPLIED_MARKER), result.output)
        assertConfiguredOnce(result)
        assertEquals(listOf("FIXTURE reporter registered access=DEVELOCITY_ISOLATED"), reporterLines(result))
        assertEquals(scanLines("DEVELOCITY_ISOLATED"), scanLines(result))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(result))
    }

    @Test
    fun `legacy gradle enterprise is used only when the plugin opts in and keeps the console`() {
        writeProject(settingsPlugins = listOf(GRADLE_ENTERPRISE, FIXTURE), properties = mapOf("fixture.legacyGradleEnterprise" to "true"))
        val optedIn = run()
        assertTrue(optedIn.output.contains(FakeGradleEnterprisePlugin.APPLIED_MARKER), optedIn.output)
        assertEquals(listOf("FIXTURE reporter registered access=GRADLE_ENTERPRISE"), reporterLines(optedIn))
        assertEquals(scanLines("GRADLE_ENTERPRISE"), scanLines(optedIn))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(optedIn))

        writeProject(settingsPlugins = listOf(GRADLE_ENTERPRISE, FIXTURE))
        val notOptedIn = run()
        assertTrue(notOptedIn.output.contains(FakeGradleEnterprisePlugin.APPLIED_MARKER), notOptedIn.output)
        assertEquals(emptyList(), reporterLines(notOptedIn))
        assertEquals(emptyList(), scanLines(notOptedIn))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(notOptedIn))
    }

    @Test
    fun `settings id plus project alias configures once`() {
        writeProject(
            settingsPlugins = listOf(DEVELOCITY, FIXTURE),
            rootPlugins = listOf(FIXTURE_ALIAS),
            properties = mapOf("fixture.consoleWithDevelocity" to "true"),
        )

        val result = run()

        assertTrue(result.output.contains("FIXTURE applied to project :"), result.output)
        assertConfiguredOnce(result)
        assertEquals(listOf("FIXTURE reporter registered access=DEVELOCITY"), reporterLines(result))
        assertEquals(scanLines("DEVELOCITY"), scanLines(result))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(result))
    }

    @Test
    fun `application from a subproject build script configures from the root`() {
        writeProject(
            settingsPlugins = listOf(DEVELOCITY),
            subprojectPlugins = listOf(FIXTURE_ALIAS),
            properties = mapOf("fixture.consoleWithDevelocity" to "true"),
        )

        val result = run()

        assertTrue(result.output.contains("FIXTURE applied to project :sub"), result.output)
        assertConfiguredOnce(result)
        assertEquals(listOf("FIXTURE reporter registered access=DEVELOCITY"), reporterLines(result))
        assertEquals(scanLines("DEVELOCITY"), scanLines(result))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(result))
    }

    @Test
    fun `two builds in the same daemon each configure once`() {
        writeProject(settingsPlugins = listOf(DEVELOCITY, FIXTURE), rootPlugins = listOf(FIXTURE_ALIAS))

        val first = run()
        val second = run("--rerun-tasks")

        assertEquals(daemonPid(first), daemonPid(second), "both builds must run in the same daemon")
        for (result in listOf(first, second)) {
            assertConfiguredOnce(result)
            assertEquals(listOf("FIXTURE reporter registered access=DEVELOCITY"), reporterLines(result))
            assertEquals(scanLines("DEVELOCITY"), scanLines(result))
        }
    }

    @Test
    fun `configuration cache reuse invokes the reporter and console with the same values`() {
        writeProject(settingsPlugins = listOf(DEVELOCITY, FIXTURE), properties = mapOf("fixture.consoleWithDevelocity" to "true"))

        val store = run("--configuration-cache")
        val reuse = run("--configuration-cache")

        assertTrue(store.output.contains("Configuration cache entry stored"), store.output)
        assertTrue(reuse.output.contains("Reusing configuration cache."), reuse.output)
        assertFalse(reuse.output.contains("FIXTURE configured"), "configuration is skipped on reuse")
        assertEquals(scanLines("DEVELOCITY"), scanLines(store))
        assertEquals(scanLines(store), scanLines(reuse))
        assertEquals(listOf(CONSOLE_LINE), consoleLines(store))
        assertEquals(consoleLines(store), consoleLines(reuse))
    }

    @Test
    fun `the configure hooks see the detected access and where the configuring application was applied`() {
        writeProject(settingsPlugins = listOf(DEVELOCITY, FIXTURE))
        val settings = run()
        assertEquals(listOf("FIXTURE configure access=DEVELOCITY"), linesStartingWith(settings, "FIXTURE configure access="))
        assertEquals(listOf("FIXTURE console rule application=SETTINGS"), linesStartingWith(settings, "FIXTURE console rule"))
        assertEquals(listOf("FIXTURE onConfigured access=DEVELOCITY present=true"), linesStartingWith(settings, "FIXTURE onConfigured"))

        writeProject(settingsPlugins = listOf(DEVELOCITY), rootPlugins = listOf(FIXTURE_ALIAS))
        val root = run()
        assertEquals(listOf("FIXTURE console rule application=ROOT_PROJECT"), linesStartingWith(root, "FIXTURE console rule"))
        assertEquals(listOf("FIXTURE onConfigured access=DEVELOCITY present=null"), linesStartingWith(root, "FIXTURE onConfigured"))

        writeProject(settingsPlugins = listOf(DEVELOCITY), subprojectPlugins = listOf(FIXTURE_ALIAS))
        val sub = run()
        assertEquals(listOf("FIXTURE console rule application=SUBPROJECT"), linesStartingWith(sub, "FIXTURE console rule"))
    }

    @Test
    fun `the settings presence provider is decided after the settings script`() {
        writeProject(settingsPlugins = listOf(FIXTURE, DEVELOCITY))
        val withDevelocity = run()
        assertTrue(withDevelocity.output.contains("present-at-apply=false"), withDevelocity.output)
        assertEquals(listOf("FIXTURE onConfigured access=DEVELOCITY present=true"), linesStartingWith(withDevelocity, "FIXTURE onConfigured"))

        writeProject(settingsPlugins = listOf(FIXTURE))
        val without = run()
        assertEquals(listOf("FIXTURE configure access=null"), linesStartingWith(without, "FIXTURE configure access="))
        assertEquals(emptyList(), linesStartingWith(without, "FIXTURE console rule"), "the rule is only read with Develocity")
        assertEquals(listOf("FIXTURE onConfigured access=null present=false"), linesStartingWith(without, "FIXTURE onConfigured"))
    }

    @Test
    fun `without the task completion subscription the console service only runs when a task uses it`() {
        writeProject(settingsPlugins = listOf(FIXTURE), properties = mapOf("fixture.subscribe" to "false", "fixture.taskUsesService" to "true"))

        val unused = run()
        assertConfiguredOnce(unused)
        assertEquals(emptyList(), consoleLines(unused))

        val used = runner().withArguments("usesFixtureService", "--stacktrace").build()
        assertTrue(used.output.contains("FIXTURE task ran"), used.output)
        assertEquals(listOf(CONSOLE_LINE), consoleLines(used))
    }

    @Test
    fun `an incompatible library version fails the build with the guard message`() {
        writeProject(settingsPlugins = listOf(FIXTURE), properties = mapOf("fixture.minLibraryVersion" to "0.2.0"))

        val result = runner().withArguments(TASK, "--stacktrace").buildAndFail()

        val loaded = System.getProperty("pluginSupport.expectedVersion")
        assertTrue(
            result.output.contains(
                "Plugin 'io.github.cdsap.pluginsupport.fixture' supports io.github.cdsap:plugin-support [0.2.0, 1.0.0), " +
                    "but version $loaded is loaded.",
            ),
            result.output,
        )
        assertTrue(result.output.contains("align the cdsap plugin versions or apply them all in settings"), result.output)
        assertFalse(result.output.contains("FIXTURE applied"), "the guard runs before any other hook")
    }

    private fun writeProject(
        settingsPlugins: List<String>,
        rootPlugins: List<String> = emptyList(),
        subprojectPlugins: List<String>? = null,
        properties: Map<String, String> = emptyMap(),
        settingsBody: String = "",
    ) {
        File(projectDir, "gradle.properties").writeText(
            (mapOf("fixture.value" to VALUE) + properties).entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" },
        )
        File(projectDir, "settings.gradle").writeText(
            buildString {
                appendLine(pluginsBlock(settingsPlugins))
                appendLine("rootProject.name = 'fixture-build'")
                if (subprojectPlugins != null) appendLine("include 'sub'")
                appendLine(settingsBody)
            },
        )
        File(projectDir, "build.gradle").writeText(pluginsBlock(rootPlugins) + "\ntasks.register('$TASK')\n")
        if (subprojectPlugins != null) {
            File(projectDir, "sub").mkdirs()
            File(projectDir, "sub/build.gradle").writeText(pluginsBlock(subprojectPlugins) + "\n")
        }
    }

    private fun pluginsBlock(ids: List<String>): String =
        if (ids.isEmpty()) "" else ids.joinToString("\n", prefix = "plugins {\n", postfix = "\n}") { "    id '$it'" }

    private fun run(vararg extraArgs: String): BuildResult = runner().withArguments(listOf(TASK, "--stacktrace") + extraArgs).build()

    private fun runner(): GradleRunner =
        GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(projectDir)
            .withPluginClasspath(pluginClasspath())

    private fun assertConfiguredOnce(result: BuildResult) {
        assertEquals(listOf("FIXTURE configured from ':'"), result.output.lines().filter { it.startsWith("FIXTURE configured") }, result.output)
    }

    private fun reporterLines(result: BuildResult) = result.output.lines().filter { it.startsWith("FIXTURE reporter registered") }

    private fun scanLines(result: BuildResult) = result.output.lines().filter { it.startsWith("SCAN-") }

    private fun consoleLines(result: BuildResult) = result.output.lines().filter { it.startsWith("FIXTURE-CONSOLE") }

    private fun daemonPid(result: BuildResult): String =
        result.output.lines().first { it.startsWith("FIXTURE applied to settings daemon=") }.substringAfter("daemon=").substringBefore(" ")

    private fun linesStartingWith(result: BuildResult, prefix: String) = result.output.lines().filter { it.startsWith(prefix) }

    private fun scanLines(access: String) =
        listOf("SCAN-VALUE fixture.value=$VALUE", "SCAN-VALUE fixture.access=$access", "SCAN-TAG fixture")

    private fun pluginClasspath(): List<File> = System.getProperty("pluginSupport.testKitClasspath").split(File.pathSeparator).map(::File)

    // Only the fake and the Develocity API: the plugin's classloader gets its own copy of DevelocityConfiguration.
    private fun initScriptClasspath(): List<File> =
        listOf(FakeDevelocityPlugin::class.java, DevelocityConfiguration::class.java, KotlinVersion::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()) }

    private companion object {
        const val TASK = "fixtureTask"
        const val VALUE = "value-1"
        const val CONSOLE_LINE = "FIXTURE-CONSOLE value=$VALUE"
        const val FIXTURE = "io.github.cdsap.pluginsupport.fixture"
        const val FIXTURE_ALIAS = "io.github.cdsap.pluginsupport.fixture.project"
        const val DEVELOCITY = "com.gradle.develocity"
        const val GRADLE_ENTERPRISE = "com.gradle.enterprise"
    }
}
