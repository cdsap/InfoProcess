package io.github.cdsap.processreport

import io.github.cdsap.plugintest.PluginUnderTest
import io.github.cdsap.processreport.ProcessReportTest.Companion.GBOS_HEADER_KEYS
import io.github.cdsap.processreport.ProcessReportTest.Companion.GBOS_HEADER_VALUES
import io.github.cdsap.processreport.ProcessReportTest.Companion.IGP_G1_CONSOLE_TABLE
import io.github.cdsap.processreport.ProcessReportTest.Companion.IGP_G1_SCAN_VALUES
import io.github.cdsap.processreport.ProcessReportTest.Companion.IKP_G1_LEGACY_VALUES
import io.github.cdsap.processreport.ProcessReportTest.Companion.IKP_TWO_DAEMON_CONSOLE_TABLE
import io.github.cdsap.processreport.ProcessReportTest.Companion.OBSERVATION_KEY
import io.github.cdsap.processreport.fixtures.FakeDaemon
import io.github.cdsap.processreport.fixtures.FakeJdkTools
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Gradle8_2ProcessReportTestKitTest : ProcessReportTestKitTest("8.2")

class Gradle9_7_1ProcessReportTestKitTest : ProcessReportTestKitTest("9.7.1")

/**
 * Drives the fixture plugin (`fixtures/ProcessReportFixturePlugin.kt`) through TestKit with fake `jps`/`jstat`/`jinfo`
 * scripts first on the build's `PATH`. The real value sources, collector, console service and reporter run; on
 * Gradle 8.2 they run against Gradle's embedded Kotlin 1.8.20 standard library, including the GBOS path through
 * build-observability-core.
 */
abstract class ProcessReportTestKitTest(private val gradleVersion: String) {
    @TempDir
    lateinit var projectDir: File

    @TempDir
    lateinit var toolsDir: File

    @Test
    fun `gradle spec prints the InfoGradleProcess console table from the real value sources`() {
        writeProject(spec = "gradle")

        val result = run(tools("GradleDaemon", "KotlinCompileDaemon", FakeJdkTools.G1_DAEMON), "help")

        assertEquals(listOf(IGP_G1_CONSOLE_TABLE), consoleTables(result), result.output)
    }

    @Test
    fun `kotlin spec prints the InfoKotlinProcess console table for two daemons`() {
        writeProject(spec = "kotlin")

        val result =
            run(tools("KotlinCompileDaemon", "GradleDaemon", FakeJdkTools.G1_DAEMON, FakeJdkTools.PARALLEL_DAEMON), "help")

        assertEquals(listOf(IKP_TWO_DAEMON_CONSOLE_TABLE), consoleTables(result), result.output)
    }

    @Test
    fun `value sources return the pipeline output and empty or failed commands return an empty string`() {
        writeProject(spec = "kotlin")
        val daemons = listOf(FakeJdkTools.G1_DAEMON, FakeJdkTools.PARALLEL_DAEMON)

        val result = run(tools("KotlinCompileDaemon", "GradleDaemon", *daemons.toTypedArray()), "probeValueSources")

        assertEquals(
            listOf(
                "PROBE jstat=[${FakeJdkTools.jStatOutput(daemons).escaped()}]",
                "PROBE jinfo=[${FakeJdkTools.jInfoOutput(daemons).escaped()}]",
                "PROBE empty=[]",
                "PROBE failing=[]",
            ),
            probeLines(result),
            result.output,
        )
    }

    @Test
    fun `without a matching daemon the value sources are empty and nothing is printed`() {
        writeProject(spec = "gradle")

        val result = run(tools("GradleDaemon", "KotlinCompileDaemon"), "probeValueSources", "probeBuildScan")

        assertEquals(EMPTY_PROBES, probeLines(result), result.output)
        assertEquals(emptyList(), scanLines(result), result.output)
        assertEquals(emptyList(), consoleTables(result), result.output)
    }

    @Test
    fun `when sh cannot be started the value sources are empty and the build does not fail`() {
        writeProject(spec = "gradle")
        val emptyPath = File(toolsDir, "empty").apply { mkdirs() }.absolutePath

        val result = run(emptyPath, "probeValueSources", "probeBuildScan")

        assertEquals(EMPTY_PROBES, probeLines(result), result.output)
        assertEquals(emptyList(), scanLines(result), result.output)
        assertEquals(emptyList(), consoleTables(result), result.output)
    }

    @Test
    fun `the build scan reporter writes legacy values per spec and gbos values through the core publisher`() {
        writeProject(spec = "gradle")
        val gradle = run(tools("GradleDaemon", "KotlinCompileDaemon", FakeJdkTools.G1_DAEMON), "probeBuildScan")
        assertEquals(IGP_G1_SCAN_VALUES, scanLines(gradle), gradle.output)

        writeProject(spec = "kotlin")
        val kotlinTools = tools("KotlinCompileDaemon", "GradleDaemon", FakeJdkTools.G1_DAEMON)
        val legacy = run(kotlinTools, "probeBuildScan")
        assertEquals(IKP_G1_LEGACY_VALUES, scanLines(legacy), legacy.output)

        val gbos = run(kotlinTools, "probeBuildScan", "-PprocessReportFixture.gbos=true")
        val runtime = gbos.output.lines().single { it.startsWith("PROCESS-FIXTURE runtime ") }
        assertTrue(runtime.contains("gradle=$gradleVersion "), runtime)
        if (gradleVersion == "8.2") assertTrue(runtime.endsWith("kotlin-stdlib=1.8.20"), runtime)
        val values = scanLines(gbos).map { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(GBOS_HEADER_KEYS + OBSERVATION_KEY, values.map { it.first }, gbos.output)
        assertEquals(GBOS_HEADER_VALUES, values.take(3).map { it.second }, gbos.output)
        assertTrue(values[3].second.startsWith("{\"scope\":\"jvm.process\",\"aggregationScope\":\"entity\""), gbos.output)
        assertTrue(values[3].second.contains("{\"name\":\"jvm.process.gc.time\",\"value\":0.6,\"unit\":\"s\",\"aggregation\":\"sum\"}"), gbos.output)
    }

    @Test
    fun `configuration cache reuse prints the same console table and build scan values`() {
        writeProject(spec = "kotlin")
        val kotlinTools = tools("KotlinCompileDaemon", "GradleDaemon", FakeJdkTools.G1_DAEMON)

        val store = run(kotlinTools, "probeBuildScan", "--configuration-cache")
        val reuse = run(kotlinTools, "probeBuildScan", "--configuration-cache")

        assertTrue(store.output.contains("Configuration cache entry stored"), store.output)
        assertTrue(reuse.output.contains("Reusing configuration cache."), reuse.output)
        assertEquals(IKP_G1_LEGACY_VALUES, scanLines(store), store.output)
        assertEquals(scanLines(store), scanLines(reuse))
        assertEquals(1, consoleTables(store).size, store.output)
        assertEquals(consoleTables(store), consoleTables(reuse))
        assertFalse(consoleTables(reuse).isEmpty())
    }

    @Test
    fun `when develocity replaces the console the jdk tools only run for the build scan`() {
        writeProject(spec = "gradle", develocity = true)
        val bin = installTools("GradleDaemon", "KotlinCompileDaemon", FakeJdkTools.G1_DAEMON)

        val result = run(pathWith(bin), "help", classpath = fakeDevelocityClasspath())

        assertEquals(IGP_G1_SCAN_VALUES, scanLines(result), result.output)
        assertEquals(emptyList(), consoleTables(result), result.output)
        assertEquals(listOf("jinfo 12345", "jstat 12345"), FakeJdkTools.calls(bin).sorted(), result.output)
    }

    @Test
    fun `when the console prints next to develocity the jdk tools run for both`() {
        writeProject(spec = "gradle", develocity = true)
        val bin = installTools("GradleDaemon", "KotlinCompileDaemon", FakeJdkTools.G1_DAEMON)

        val result = run(pathWith(bin), "help", "-PprocessReportFixture.consoleWithDevelocity=true", classpath = fakeDevelocityClasspath())

        assertEquals(IGP_G1_SCAN_VALUES, scanLines(result), result.output)
        assertEquals(listOf(IGP_G1_CONSOLE_TABLE), consoleTables(result), result.output)
        assertEquals(listOf("jinfo 12345", "jinfo 12345", "jstat 12345", "jstat 12345"), FakeJdkTools.calls(bin).sorted(), result.output)
    }

    private fun writeProject(spec: String, develocity: Boolean = false) {
        File(projectDir, "gradle.properties").writeText("processReportFixture.spec=$spec\n")
        File(projectDir, "settings.gradle").writeText(
            """
            plugins {
                ${if (develocity) "id 'com.gradle.develocity'" else ""}
                id 'io.github.cdsap.processreport.fixture'
            }
            rootProject.name = 'process-report-fixture'
            """.trimIndent(),
        )
        File(projectDir, "build.gradle").writeText("")
    }

    /** A `PATH` with the fake tools first. */
    private fun tools(processName: String, otherProcessName: String, vararg daemons: FakeDaemon): String =
        pathWith(installTools(processName, otherProcessName, *daemons))

    private fun installTools(processName: String, otherProcessName: String, vararg daemons: FakeDaemon): File =
        FakeJdkTools.install(File(toolsDir, "bin-${System.nanoTime()}"), processName, otherProcessName, daemons.toList())

    private fun pathWith(bin: File) = "${bin.absolutePath}${File.pathSeparator}${System.getenv("PATH")}"

    private fun testKitClasspath(): List<File> = System.getProperty("processReport.testKitClasspath").split(File.pathSeparator).map(::File)

    private fun fakeDevelocityClasspath(): List<File> = PluginUnderTest.withFakeDevelocity(File(toolsDir, "develocity-api"), testKitClasspath())

    private fun run(path: String, vararg arguments: String, classpath: List<File> = testKitClasspath()): BuildResult =
        GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(projectDir)
            .withPluginClasspath(classpath)
            .withEnvironment(System.getenv() + ("PATH" to path))
            .withArguments(arguments.toList() + "--stacktrace")
            .build()

    private fun probeLines(result: BuildResult) = result.output.lines().filter { it.startsWith("PROBE ") }

    private fun scanLines(result: BuildResult) = result.output.lines().filter { it.startsWith("SCAN-VALUE ") }.map { it.removePrefix("SCAN-VALUE ") }

    private fun consoleTables(result: BuildResult): List<String> {
        val lines = result.output.lines()
        return lines.indices
            .filter { lines[it].startsWith("┌") }
            .map { start ->
                val end = (start until lines.size).first { lines[it].startsWith("└") }
                lines.subList(start, end + 1).joinToString("\n")
            }
    }

    private fun String.escaped() = replace("\n", "\\n")

    private companion object {
        val EMPTY_PROBES = listOf("PROBE jstat=[]", "PROBE jinfo=[]", "PROBE empty=[]", "PROBE failing=[]")
    }
}
