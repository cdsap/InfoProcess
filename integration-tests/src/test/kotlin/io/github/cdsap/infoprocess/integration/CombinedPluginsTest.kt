package io.github.cdsap.infoprocess.integration

import io.github.cdsap.plugintest.BuildScript
import io.github.cdsap.plugintest.ConfigurationCache
import io.github.cdsap.plugintest.ConfigurationCacheRuns
import io.github.cdsap.plugintest.FakeDevelocity
import io.github.cdsap.plugintest.IsolatedProjects
import io.github.cdsap.plugintest.SettingsScript
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 1 gate of the combined-plugins suite: the four fixture plugins under `integration-tests/fixture-plugins/`
 * applied together in one TestKit build, resolved from the local Maven repository that `:integration-tests:test`
 * publishes first (`build/local-repo`), never through `withPluginClasspath`. That repository also holds
 * `plugin-support` and `jvm-process-report` at 1.0.0, 1.1.0 and 2.0.0, so the builds exercise Gradle's real plugin
 * resolution and parent-first classloading, which the version guard depends on.
 *
 * The Gradle version comes from `-Dintegration.gradleVersion` (CI runs 8.2, 8.14.3 and 9.7.1); without it TestKit
 * runs the wrapper's version. Fake `jps`/`jstat`/`jinfo` scripts first on the build's `PATH` report one Gradle daemon
 * (G1, pid 12345) and one Kotlin daemon (Parallel, pid 23456), so the process fixtures print golden values.
 */
class CombinedPluginsTest {
    @TempDir
    lateinit var projectDir: File

    @TempDir
    lateinit var workDir: File

    @Test
    fun `all fixture plugins without Develocity print their own console output and nothing collides`() {
        settings().allFixtures().writeTo(projectDir)
        writeRootBuild()

        val result = runner("help").build()

        assertAppliedOnce(result, pluginSupport = "1.1.0")
        assertEquals(listOf(DUAL_ENTRY_CONSOLE, SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(result), result.output)
        assertEquals(listOf(GRADLE_PROCESSES_TABLE, KOTLIN_PROCESSES_TABLE), consoleTablesByTitle(result), result.output)
        assertEquals(emptyList(), FakeDevelocity.scanValues(result.output), result.output)
    }

    @Test
    fun `with the fake Develocity each fixture's keys reach the build scan`() {
        settings().withFakeDevelocity().allFixtures().writeTo(projectDir)
        writeRootBuild()

        val result = runner("help").build()

        assertTrue(result.output.contains(FakeDevelocity.APPLIED_MARKER), result.output)
        assertScanValues(
            result,
            SETTINGS_ONLY_VALUES,
            dualEntryValues("DEVELOCITY"),
            GRADLE_SPEC_VALUES,
            KOTLIN_SPEC_VALUES,
        )
        assertEquals(listOf("gc", "tests:passed"), FakeDevelocity.scanTags(result.output).sorted(), result.output)
        // Settings-only always prints; the others leave the console to Develocity by default.
        assertEquals(listOf(SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(result), result.output)
        assertEquals(emptyList(), consoleTables(result), result.output)
    }

    @Test
    fun `with the fake Gradle Enterprise plugin only the fixture that opts into the legacy lookup reports`() {
        settings()
            .fakeDevelocity(Lane.fixturesVersion, develocityApiJar())
            .plugin(FakeDevelocity.LEGACY_PLUGIN_ID)
            .allFixtures()
            .writeTo(projectDir)
        writeRootBuild()

        val result = runner("help").build()

        assertTrue(result.output.contains(FakeDevelocity.LEGACY_APPLIED_MARKER), result.output)
        assertScanValues(result, dualEntryValues("GRADLE_ENTERPRISE"))
        assertEquals(listOf("gc"), FakeDevelocity.scanTags(result.output), result.output)
        assertEquals(listOf(DUAL_ENTRY_CONSOLE, SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(result), result.output)
        assertEquals(listOf(GRADLE_PROCESSES_TABLE, KOTLIN_PROCESSES_TABLE), consoleTablesByTitle(result), result.output)
    }

    @Test
    fun `GBOS values from the Kotlin-spec and settings-only fixtures go through the core publisher`() {
        settings().withFakeDevelocity().allFixtures().writeTo(projectDir)
        writeRootBuild()
        writeGradleProperties("settingsOnly.gbos.enabled" to "true", "kotlinSpec.gbos.enabled" to "true")

        val result = runner("help").build()

        val settingsOnly = result.output.lines().single { it.startsWith("SETTINGS-ONLY applied ") }
        assertTrue(settingsOnly.contains(" gradle=${Lane.gradleVersion} "), settingsOnly)
        if (Lane.gradleVersion == "8.2") assertTrue(settingsOnly.endsWith(" kotlin-stdlib=1.8.20"), settingsOnly)

        val values = FakeDevelocity.scanValues(result.output)
        val kotlinObservation = values.single { it.startsWith("$KOTLIN_GBOS_OBSERVATION_KEY=") }.substringAfter('=')
        assertTrue(kotlinObservation.startsWith("{\"scope\":\"jvm.process\",\"aggregationScope\":\"entity\""), kotlinObservation)
        assertTrue(kotlinObservation.contains("\"process.pid\":23456"), kotlinObservation)
        assertTrue(kotlinObservation.contains("\"jvm.process.role\":\"kotlin-daemon\""), kotlinObservation)
        assertTrue(
            kotlinObservation.contains("{\"name\":\"jvm.process.gc.time\",\"value\":120,\"unit\":\"s\",\"aggregation\":\"sum\"}"),
            kotlinObservation,
        )
        assertScanValues(
            result,
            SETTINGS_ONLY_GBOS_VALUES,
            dualEntryValues("DEVELOCITY"),
            GRADLE_SPEC_VALUES,
            KOTLIN_SPEC_GBOS_HEADER + "$KOTLIN_GBOS_OBSERVATION_KEY=$kotlinObservation",
        )
    }

    @Test
    fun `an init-script-injected fake Develocity receives the values and console output still appears`() {
        settings().allFixtures().writeTo(projectDir)
        writeRootBuild()
        val initScript = FakeDevelocity.writeInitScript(File(workDir, "fake-develocity.init.gradle"), develocityApiJar())

        val result = runner("help", "--init-script", initScript.absolutePath).build()

        assertTrue(result.output.contains(FakeDevelocity.APPLIED_MARKER), result.output)
        assertScanValues(
            result,
            SETTINGS_ONLY_VALUES,
            dualEntryValues("DEVELOCITY_ISOLATED"),
            GRADLE_SPEC_VALUES,
            KOTLIN_SPEC_VALUES,
        )
        assertEquals(listOf(DUAL_ENTRY_CONSOLE, SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(result), result.output)
        assertEquals(listOf(GRADLE_PROCESSES_TABLE, KOTLIN_PROCESSES_TABLE), consoleTablesByTitle(result), result.output)
    }

    @Test
    fun `an older library loaded from settings fails the root build script fixture with the guard message`() {
        settings().plugin(SETTINGS_ONLY, Lane.fixturePluginVersion).writeTo(projectDir)
        writeRootBuild(BuildScript().plugin(DUAL_ENTRY, Lane.fixturePluginVersion))

        val result = runner("help").buildAndFail()

        assertTrue(result.output.contains("SETTINGS-ONLY applied plugin-support=1.0.0 "), result.output)
        assertTrue(result.output.contains(guardMessage(DUAL_ENTRY, "1.1.0", "2.0.0", loaded = "1.0.0")), result.output)
        assertNoLinkageErrors(result)
        assertEquals(emptyList(), result.tasks, result.output)
    }

    @Test
    fun `without the guard the same skew fails with NoSuchMethodError, which the guard turns into a fix-it message`() {
        settings().plugin(SETTINGS_ONLY, Lane.fixturePluginVersion).writeTo(projectDir)
        writeRootBuild(BuildScript().plugin(DUAL_ENTRY, Lane.fixturePluginVersion))

        val result = runner("help", "-PdualEntry.skipGuard=true").buildAndFail()

        assertTrue(result.output.contains("NoSuchMethodError"), result.output)
        assertTrue(result.output.contains("LibrarySkewProbe.since110"), result.output)
    }

    @Test
    fun `a buildscript pin to the next library major fails with the guard message`() {
        settings()
            .buildscriptClasspath("io.github.cdsap:plugin-support:2.0.0")
            .plugin(SETTINGS_ONLY, Lane.fixturePluginVersion)
            .writeTo(projectDir)
        writeRootBuild()

        val result = runner("help").buildAndFail()

        assertTrue(result.output.contains(guardMessage(SETTINGS_ONLY, "1.0.0", "2.0.0", loaded = "2.0.0")), result.output)
        assertNoLinkageErrors(result)
        assertFalse(result.output.contains("SETTINGS-ONLY applied"), result.output)
    }

    @Test
    fun `fixtures built against 1_0 and 1_1 succeed when the build resolves 1_1`() {
        settings()
            .plugin(SETTINGS_ONLY, Lane.fixturePluginVersion)
            .plugin(DUAL_ENTRY, Lane.fixturePluginVersion)
            .writeTo(projectDir)
        writeRootBuild()

        val bothInSettings = runner("help").build()

        assertTrue(bothInSettings.output.contains("SETTINGS-ONLY applied plugin-support=1.1.0 "), bothInSettings.output)
        assertTrue(bothInSettings.output.contains("DUAL-ENTRY applied to settings plugin-support=1.1.0 api=1.1.0"), bothInSettings.output)
        assertEquals(listOf(DUAL_ENTRY_CONSOLE, SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(bothInSettings), bothInSettings.output)

        settings()
            .buildscriptClasspath("io.github.cdsap:plugin-support:1.1.0")
            .plugin(SETTINGS_ONLY, Lane.fixturePluginVersion)
            .writeTo(projectDir)
        writeRootBuild(BuildScript().plugin(DUAL_ENTRY, Lane.fixturePluginVersion))

        val rootBuildScript = runner("help").build()

        assertTrue(rootBuildScript.output.contains("SETTINGS-ONLY applied plugin-support=1.1.0 "), rootBuildScript.output)
        assertTrue(
            rootBuildScript.output.contains("DUAL-ENTRY applied to project : plugin-support=1.1.0 api=1.1.0"),
            rootBuildScript.output,
        )
        assertEquals(listOf(DUAL_ENTRY_CONSOLE, SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(rootBuildScript), rootBuildScript.output)
    }

    @Test
    fun `configuration cache store then reuse prints identical output with the fake Develocity`() {
        settings().withFakeDevelocity().allFixtures().writeTo(projectDir)
        writeRootBuild()
        writeGradleProperties("dualEntry.consoleWithDevelocity" to "true")

        val runs = ConfigurationCache.storeAndReuse(runner(), "help", "--stacktrace")

        assertScanValues(runs.store, SETTINGS_ONLY_VALUES, dualEntryValues("DEVELOCITY"), GRADLE_SPEC_VALUES, KOTLIN_SPEC_VALUES)
        assertIdenticalOutput(runs)
        assertEquals(listOf(DUAL_ENTRY_CONSOLE, SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(runs.reuse), runs.reuse.output)
    }

    @Test
    fun `configuration cache store then reuse prints identical console tables without Develocity`() {
        settings().allFixtures().writeTo(projectDir)
        writeRootBuild()

        val runs = ConfigurationCache.storeAndReuse(runner(), "help", "--stacktrace")

        assertIdenticalOutput(runs)
        assertEquals(listOf(GRADLE_PROCESSES_TABLE, KOTLIN_PROCESSES_TABLE), consoleTablesByTitle(runs.reuse), runs.reuse.output)
        assertEquals(listOf(DUAL_ENTRY_CONSOLE, SETTINGS_ONLY_CONSOLE), fixtureConsoleLines(runs.reuse), runs.reuse.output)
    }

    @Test
    fun `isolated projects stores and reuses the dual-entry settings entry without violations`() {
        settings()
            .plugin(DUAL_ENTRY, Lane.fixturePluginVersion)
            .include("sub")
            .writeTo(projectDir)
        writeRootBuild()
        BuildScript().writeTo(File(projectDir, "sub"))

        val runs = IsolatedProjects.storeAndReuse(runner(), "help", "--stacktrace")

        assertTrue(runs.store.output.contains("DUAL-ENTRY applied to settings plugin-support=1.1.0 api=1.1.0"), runs.store.output)
        assertEquals(listOf(DUAL_ENTRY_CONSOLE), fixtureConsoleLines(runs.store), runs.store.output)
        assertEquals(listOf(DUAL_ENTRY_CONSOLE), fixtureConsoleLines(runs.reuse), runs.reuse.output)
    }

    @Test
    fun `the real Develocity plugin receives every fixture's values on configuration cache store and reuse`() {
        settings()
            .plugin(FakeDevelocity.PLUGIN_ID, Lane.develocityVersion)
            .allFixtures()
            .append("develocity { buildScan { publishing.onlyIf { false } } }")
            .writeTo(projectDir)
        writeRootBuild()
        writeGradleProperties("fixture.echoReporter" to "true")

        val runs = ConfigurationCache.storeAndReuse(runner(), "help", "--stacktrace")

        val expected = SETTINGS_ONLY_VALUES + dualEntryValues("DEVELOCITY") + GRADLE_SPEC_VALUES + KOTLIN_SPEC_VALUES
        assertEquals(expected.sorted(), reportedValues(runs.store).sorted(), runs.store.output)
        assertEquals(reportedValues(runs.store), reportedValues(runs.reuse), runs.reuse.output)
        assertEquals(listOf("gc", "tests:passed"), reportedTags(runs.reuse).sorted(), runs.reuse.output)
        assertFalse(runs.store.output.contains(FakeDevelocity.APPLIED_MARKER), runs.store.output)
    }

    /**
     * Plan scenario "each baseline plugin release (Phase 0) next to the fixture plugins in one build succeeds", with
     * released project plugins applied in the root build script. Deferred: the Phase 0 baseline releases (KTD9) are
     * not on the Plugin Portal yet. Once they are, request each released id and version here next to the fixtures
     * and assert both outputs.
     */
    @Disabled("Deferred until the Phase 0 baseline plugin releases are on the Gradle Plugin Portal (KTD9).")
    @Test
    fun `baseline plugin releases next to the fixture plugins succeed`() {
    }

    private fun settings(): SettingsScript = SettingsScript().localMavenRepository(Lane.localRepo).rootProjectName("combined")

    private fun SettingsScript.withFakeDevelocity(): SettingsScript =
        fakeDevelocity(Lane.fixturesVersion, develocityApiJar()).plugin(FakeDevelocity.PLUGIN_ID)

    private fun SettingsScript.allFixtures(): SettingsScript =
        plugin(SETTINGS_ONLY, Lane.fixturePluginVersion)
            .plugin(DUAL_ENTRY, Lane.fixturePluginVersion)
            .plugin(GRADLE_SPEC, Lane.fixturePluginVersion)
            .plugin(KOTLIN_SPEC, Lane.fixturePluginVersion)

    private fun develocityApiJar(): File = FakeDevelocity.develocityApiJar(File(workDir, "develocity-api"))

    private fun writeRootBuild(script: BuildScript = BuildScript()) {
        script.writeTo(projectDir)
    }

    private fun writeGradleProperties(vararg properties: Pair<String, String>) {
        File(projectDir, "gradle.properties").writeText(properties.joinToString("") { (key, value) -> "$key=$value\n" })
    }

    private fun runner(vararg arguments: String): GradleRunner {
        val tools = FakeJdkTools.install(File(workDir, "bin"), listOf(FakeJdkTools.GRADLE_DAEMON, FakeJdkTools.KOTLIN_DAEMON))
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withEnvironment(System.getenv() + ("PATH" to "${tools.absolutePath}${File.pathSeparator}${System.getenv("PATH")}"))
            .withArguments(arguments.toList() + "--stacktrace")
            .also { runner -> Lane.requestedGradleVersion?.let { runner.withGradleVersion(it) } }
    }

    private fun assertAppliedOnce(result: BuildResult, pluginSupport: String) {
        val applied = result.output.lines().filter { line -> APPLIED_PREFIXES.any { line.startsWith(it) } }
        assertEquals(APPLIED_PREFIXES, applied.map { line -> APPLIED_PREFIXES.single { line.startsWith(it) } }, result.output)
        assertTrue(applied.all { it.contains("plugin-support=$pluginSupport") }, result.output)
    }

    /**
     * The build's Build Scan values are exactly the union of [groups], and each group appears in its own order, so
     * no fixture lost, duplicated or overwrote another's key.
     */
    private fun assertScanValues(result: BuildResult, vararg groups: List<String>) {
        val actual = FakeDevelocity.scanValues(result.output)
        assertEquals(groups.toList().flatten().sorted(), actual.sorted(), result.output)
        groups.forEach { group -> assertTrue(isSubsequence(group, actual), "Expected $group in order in $actual") }
    }

    private fun isSubsequence(expected: List<String>, actual: List<String>): Boolean {
        val remaining = actual.iterator()
        return expected.all { wanted -> remaining.asSequence().any { it == wanted } }
    }

    private fun assertIdenticalOutput(runs: ConfigurationCacheRuns) {
        assertEquals(FakeDevelocity.scanValues(runs.store.output), FakeDevelocity.scanValues(runs.reuse.output), runs.reuse.output)
        assertEquals(FakeDevelocity.scanTags(runs.store.output), FakeDevelocity.scanTags(runs.reuse.output), runs.reuse.output)
        assertEquals(fixtureConsoleLines(runs.store), fixtureConsoleLines(runs.reuse), runs.reuse.output)
        assertEquals(consoleTables(runs.store), consoleTables(runs.reuse), runs.reuse.output)
    }

    private fun assertNoLinkageErrors(result: BuildResult) {
        assertFalse(result.output.contains("NoSuchMethodError"), result.output)
        assertFalse(result.output.contains("NoClassDefFoundError"), result.output)
    }

    private fun guardMessage(pluginId: String, min: String, max: String, loaded: String) =
        "Plugin '$pluginId' supports io.github.cdsap:plugin-support [$min, $max), but version $loaded is loaded."

    private fun fixtureConsoleLines(result: BuildResult): List<String> =
        result.output.lines().filter { it.startsWith("SETTINGS-ONLY-CONSOLE ") || it.startsWith("DUAL-ENTRY-CONSOLE ") }.sorted()

    private fun reportedValues(result: BuildResult) =
        result.output.lines().filter { it.startsWith("REPORTED-VALUE ") }.map { it.removePrefix("REPORTED-VALUE ") }

    private fun reportedTags(result: BuildResult) =
        result.output.lines().filter { it.startsWith("REPORTED-TAG ") }.map { it.removePrefix("REPORTED-TAG ") }

    private fun consoleTables(result: BuildResult): List<String> {
        val lines = result.output.lines()
        return lines.indices
            .filter { lines[it].startsWith("┌") }
            .map { start ->
                val end = (start until lines.size).first { lines[it].startsWith("└") }
                lines.subList(start, end + 1).joinToString("\n")
            }
    }

    private fun consoleTablesByTitle(result: BuildResult): List<String> = consoleTables(result).sortedBy { it.lines()[1] }

    private object Lane {
        val requestedGradleVersion: String? = System.getProperty("integration.gradleVersion")?.takeIf { it.isNotBlank() }
        val gradleVersion: String = requestedGradleVersion ?: System.getProperty("integration.defaultGradleVersion")
        val localRepo = File(System.getProperty("integration.localRepo"))
        val fixturesVersion: String = System.getProperty("integration.fixturesVersion")
        val fixturePluginVersion: String = System.getProperty("integration.fixturePluginVersion")
        val develocityVersion: String = System.getProperty("integration.develocityVersion")
    }

    private companion object {
        const val SETTINGS_ONLY = "io.github.cdsap.fixture.settings-only"
        const val DUAL_ENTRY = "io.github.cdsap.fixture.dual-entry"
        const val GRADLE_SPEC = "io.github.cdsap.fixture.gradle-spec"
        const val KOTLIN_SPEC = "io.github.cdsap.fixture.kotlin-spec"

        val APPLIED_PREFIXES = listOf("SETTINGS-ONLY applied ", "DUAL-ENTRY applied ", "GRADLE-SPEC applied ", "KOTLIN-SPEC applied ")

        const val SETTINGS_ONLY_CONSOLE = "SETTINGS-ONLY-CONSOLE tests=3 failed=0"
        const val DUAL_ENTRY_CONSOLE = "DUAL-ENTRY-CONSOLE collections=2"

        val SETTINGS_ONLY_VALUES = listOf("settingsOnly.tests=3", "settingsOnly.failed=0")

        fun dualEntryValues(access: String) = listOf("dualEntry.collections=2", "dualEntry.access=$access")

        val GRADLE_SPEC_VALUES =
            listOf(
                "Gradle-Process-12345-max=4.0 GB",
                "Gradle-Process-12345-usage=1.27 GB",
                "Gradle-Process-12345-capacity=1.94 GB",
                "Gradle-Process-12345-uptime=18.63 minutes",
                "Gradle-Process-12345-gcTime=0.01 minutes",
                "Gradle-Process-12345-gcType=-XX:+UseG1GC",
            )

        val KOTLIN_SPEC_VALUES =
            listOf(
                "Kotlin-Process-23456-max=2.0 GB",
                "Kotlin-Process-23456-usage=1.0 GB",
                "Kotlin-Process-23456-capacity=1.52 GB",
                "Kotlin-Process-23456-uptime=60.0 minutes",
                "Kotlin-Process-23456-gcTime=2.0 minutes",
                "Kotlin-Process-23456-gcType=-XX:+UseParallelGC",
            )

        private const val TEST_COUNT = "{\"name\":\"test.count\",\"value\":3,\"unit\":\"count\",\"aggregation\":\"sum\"}"

        val SETTINGS_ONLY_GBOS_VALUES =
            listOf(
                "gbos.schema=1.0.0",
                "gbos.v1.producer.settings_only_fixture.version=0.0.4",
                "gbos.v1.producer.settings_only_fixture.name=settings-only-fixture",
                "gbos.v1.producer.settings_only_fixture.observation=" +
                    "{\"scope\":\"test.task\",\"aggregationScope\":\"entity\",\"attributes\":{\"test.task.path\":\":test\"}," +
                    "\"measurements\":[$TEST_COUNT]}",
                "gbos.v1.producer.settings_only_fixture.observation=" +
                    "{\"scope\":\"build\",\"aggregationScope\":\"build\",\"attributes\":{},\"measurements\":[$TEST_COUNT]}",
                "gbos.v1.index.settings_only_fixture.test.count.sum=3",
            )

        val KOTLIN_SPEC_GBOS_HEADER =
            listOf(
                "gbos.schema=1.0.0",
                "gbos.v1.producer.kotlin_spec_fixture.version=0.0.4",
                "gbos.v1.producer.kotlin_spec_fixture.name=kotlin-spec-fixture",
            )

        const val KOTLIN_GBOS_OBSERVATION_KEY = "gbos.v1.producer.kotlin_spec_fixture.observation"

        val GRADLE_PROCESSES_TABLE =
            """
            ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
            │  Gradle processes                                                                               │
            ├─────────┬──────────┬───────────┬────────────┬────────────────┬────────────────┬─────────────────┤
            │  PID    │  Max     │  Usage    │  Capacity  │  GC Time       │  GC Type       │  Uptime         │
            ├─────────┼──────────┼───────────┼────────────┼────────────────┼────────────────┼─────────────────┤
            │  12345  │  4.0 Gb  │  1.27 Gb  │  1.94 Gb   │  0.01 minutes  │  -XX:+UseG1GC  │  18.63 minutes  │
            └─────────┴──────────┴───────────┴────────────┴────────────────┴────────────────┴─────────────────┘
            """.trimIndent()

        val KOTLIN_PROCESSES_TABLE =
            """
            ┌────────────────────────────────────────────────────────────────────────────────────────────────────┐
            │  Kotlin processes                                                                                  │
            ├─────────┬──────────┬──────────┬────────────┬───────────────┬──────────────────────┬────────────────┤
            │  PID    │  Max     │  Usage   │  Capacity  │  GC Time      │  GC Type             │  Uptime        │
            ├─────────┼──────────┼──────────┼────────────┼───────────────┼──────────────────────┼────────────────┤
            │  23456  │  2.0 Gb  │  1.0 Gb  │  1.52 Gb   │  2.0 minutes  │  -XX:+UseParallelGC  │  60.0 minutes  │
            └─────────┴──────────┴──────────┴────────────┴───────────────┴──────────────────────┴────────────────┘
            """.trimIndent()
    }
}
