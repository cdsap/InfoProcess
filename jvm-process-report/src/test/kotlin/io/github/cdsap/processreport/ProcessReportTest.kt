package io.github.cdsap.processreport

import groovy.json.JsonSlurper
import io.github.cdsap.jdk.tools.parser.model.Process
import io.github.cdsap.jdk.tools.parser.model.TypeProcess
import io.github.cdsap.pluginsupport.BuildScanSink
import io.github.cdsap.processreport.fixtures.FakeDaemon
import io.github.cdsap.processreport.fixtures.FakeJdkTools
import io.github.cdsap.processreport.fixtures.IkpReferenceProjection
import io.github.cdsap.processreport.fixtures.TestSpecs
import org.gradle.testfixtures.ProjectBuilder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The library against the InfoGradleProcess and InfoKotlinProcess characterization goldens (copied from their
 * `CharacterizationTest`s), using the same `jstat`/`jinfo` fixture output.
 */
class ProcessReportTest {
    // ---------------------------------------------------------------- collector

    @Test
    fun `gradle spec collects gradle daemons as TypeProcess Gradle`() {
        assertEquals(
            listOf(Process("12345", 4.0, 1.27, 1.94, 0.01, 18.63, "-XX:+UseG1GC", TypeProcess.Gradle)),
            collect(TestSpecs.GRADLE, FakeJdkTools.G1_DAEMON),
        )
    }

    @Test
    fun `kotlin spec collects kotlin daemons as TypeProcess Kotlin`() {
        assertEquals(
            listOf(Process("12345", 4.0, 1.27, 1.94, 0.01, 18.63, "-XX:+UseG1GC", TypeProcess.Kotlin)),
            collect(TestSpecs.KOTLIN, FakeJdkTools.G1_DAEMON),
        )
    }

    @Test
    fun `empty value-source output collects no process and writes nothing`() {
        for (spec in listOf(TestSpecs.GRADLE, TestSpecs.KOTLIN)) {
            val processes = ProcessReport.collect(spec, "", "")
            assertEquals(emptyList(), processes)
            assertEquals(emptyList(), values(spec, processes, gbosEnabled = false))
            assertEquals(emptyList(), values(spec, processes, gbosEnabled = true))
        }
    }

    // ---------------------------------------------------------------- legacy custom values

    @Test
    fun `gradle spec with one daemon emits exactly the InfoGradleProcess golden values`() {
        assertEquals(IGP_G1_SCAN_VALUES, legacy(TestSpecs.GRADLE, FakeJdkTools.G1_DAEMON))
    }

    @Test
    fun `gradle spec with two daemons groups values per process in jstat order`() {
        assertEquals(
            IGP_G1_SCAN_VALUES + IGP_PARALLEL_SCAN_VALUES,
            legacy(TestSpecs.GRADLE, FakeJdkTools.G1_DAEMON, FakeJdkTools.PARALLEL_DAEMON),
        )
        assertEquals(
            IGP_PARALLEL_SCAN_VALUES + IGP_G1_SCAN_VALUES,
            legacy(TestSpecs.GRADLE, FakeJdkTools.PARALLEL_DAEMON, FakeJdkTools.G1_DAEMON),
        )
    }

    @Test
    fun `kotlin spec with one daemon emits exactly the InfoKotlinProcess golden values`() {
        assertEquals(IKP_G1_LEGACY_VALUES, legacy(TestSpecs.KOTLIN, FakeJdkTools.G1_DAEMON))
    }

    @Test
    fun `kotlin spec with two daemons groups values per process in jstat order`() {
        assertEquals(
            IKP_G1_LEGACY_VALUES + IKP_PARALLEL_LEGACY_VALUES,
            legacy(TestSpecs.KOTLIN, FakeJdkTools.G1_DAEMON, FakeJdkTools.PARALLEL_DAEMON),
        )
    }

    @Test
    fun `the key prefix comes from the spec even when the process type disagrees`() {
        val kotlinTyped = Process("12345", 4.0, 1.27, 1.94, 0.01, 18.63, "-XX:+UseG1GC", TypeProcess.Kotlin)
        val gradleTyped = kotlinTyped.copy(typeProcess = TypeProcess.Gradle)

        assertEquals(IGP_G1_SCAN_VALUES, values(TestSpecs.GRADLE, listOf(kotlinTyped), gbosEnabled = false))
        assertEquals(IKP_G1_LEGACY_VALUES, values(TestSpecs.KOTLIN, listOf(gradleTyped), gbosEnabled = false))
        assertEquals(IGP_G1_CONSOLE_TABLE, ProcessReport.consoleTable(TestSpecs.GRADLE, listOf(kotlinTyped)))
        assertEquals(IKP_G1_CONSOLE_TABLE, ProcessReport.consoleTable(TestSpecs.KOTLIN, listOf(gradleTyped)))
    }

    @Test
    fun `gbos opt-in is ignored by a spec without a gbos producer`() {
        val processes = collect(TestSpecs.GRADLE, FakeJdkTools.G1_DAEMON)

        assertEquals(IGP_G1_SCAN_VALUES, values(TestSpecs.GRADLE, processes, gbosEnabled = true))
    }

    // ---------------------------------------------------------------- console table

    @Test
    fun `console tables match the plugins' golden text for one daemon`() {
        assertEquals(IGP_G1_CONSOLE_TABLE, ProcessReport.consoleTable(TestSpecs.GRADLE, collect(TestSpecs.GRADLE, FakeJdkTools.G1_DAEMON)))
        assertEquals(IKP_G1_CONSOLE_TABLE, ProcessReport.consoleTable(TestSpecs.KOTLIN, collect(TestSpecs.KOTLIN, FakeJdkTools.G1_DAEMON)))
    }

    @Test
    fun `console tables match the plugins' golden text for two daemons`() {
        val daemons = arrayOf(FakeJdkTools.G1_DAEMON, FakeJdkTools.PARALLEL_DAEMON)

        assertEquals(IKP_TWO_DAEMON_CONSOLE_TABLE, ProcessReport.consoleTable(TestSpecs.KOTLIN, collect(TestSpecs.KOTLIN, *daemons)))
        // Both titles are 16 characters wide, so the Gradle table only differs in its title.
        assertEquals(
            IKP_TWO_DAEMON_CONSOLE_TABLE.replace("Kotlin processes", "Gradle processes"),
            ProcessReport.consoleTable(TestSpecs.GRADLE, collect(TestSpecs.GRADLE, *daemons)),
        )
    }

    // ---------------------------------------------------------------- GBOS

    @Test
    fun `kotlin spec with gbos emits the header then one observation per daemon`() {
        val values = gbos(FakeJdkTools.G1_DAEMON, FakeJdkTools.PARALLEL_DAEMON)

        assertEquals(GBOS_HEADER_KEYS + listOf(OBSERVATION_KEY, OBSERVATION_KEY), values.map { it.first })
        assertEquals(GBOS_HEADER_VALUES, values.take(3).map { it.second })
        assertG1Observation(values[3].second)
        assertObservation(
            values[4].second,
            pid = 23456,
            gcName = "Parallel",
            heapLimitBytes = 2147483648.0,
            heapUsedBytes = 1073741824.0,
            heapCommittedBytes = 1632087572.0,
            gcTimeSeconds = 120.0,
            uptimeSeconds = 3600.0,
        )
    }

    @Test
    fun `gbos output differs from InfoKotlinProcess today only in header order and number rendering`() {
        val processes = collect(TestSpecs.KOTLIN, FakeJdkTools.G1_DAEMON, FakeJdkTools.PARALLEL_DAEMON)
        val library = record { ProcessReport.writeBuildScanValues(TestSpecs.KOTLIN, processes, true, it) }
        val reference = IkpReferenceProjection.values(processes)

        assertEquals(reference.size, library.size)
        // The core publisher writes `.version` before `.name`; InfoKotlinProcess writes `.name` first.
        assertEquals(listOf(reference[0], reference[2], reference[1]), library.take(3))
        reference.drop(3).zip(library.drop(3)).forEach { (expected, actual) ->
            assertEquals(expected.first, actual.first)
            assertEquals(parse(expected.second), parse(actual.second), "parsed observation")
            // Groovy JsonOutput renders whole doubles as `15.0`; GbosJson renders them as `15`.
            assertEquals(expected.second.replace(Regex("""(\d)\.0(?=[,}\]])"""), "$1"), actual.second)
        }
    }

    @Test
    fun `kotlin spec without the gbos opt-in emits legacy values`() {
        assertEquals(IKP_G1_LEGACY_VALUES, values(TestSpecs.KOTLIN, collect(TestSpecs.KOTLIN, FakeJdkTools.G1_DAEMON), gbosEnabled = false))
    }

    @Test
    fun `a non numeric pid fails the gbos projection with the spec's process name`() {
        val process = Process("abc", 1.0, 0.5, 0.75, 0.25, 2.5, "-XX:+UseG1GC", TypeProcess.Kotlin)

        val failure = assertFailsWith<IllegalArgumentException> { values(TestSpecs.KOTLIN, listOf(process), gbosEnabled = true) }
        assertEquals("KotlinCompileDaemon PID must be numeric: abc", failure.message)
    }

    // ---------------------------------------------------------------- reporter

    @Test
    fun `the build scan reporter reads its providers when it reports`() {
        val providers = ProjectBuilder.builder().build().providers
        var gbosEnabled = false
        val reporter =
            ProcessReport.buildScanReporter(
                TestSpecs.KOTLIN,
                providers.provider { FakeJdkTools.jStatOutput(listOf(FakeJdkTools.G1_DAEMON)) },
                providers.provider { FakeJdkTools.jInfoOutput(listOf(FakeJdkTools.G1_DAEMON)) },
                providers.provider { gbosEnabled },
            )

        assertEquals(IKP_G1_LEGACY_VALUES, record(reporter::report).map { "${it.first}=${it.second}" })
        gbosEnabled = true
        assertEquals(GBOS_HEADER_KEYS + OBSERVATION_KEY, record(reporter::report).map { it.first })
    }

    @Test
    fun `the build scan reporter does not read the gbos opt-in for a spec without gbos`() {
        val providers = ProjectBuilder.builder().build().providers
        val reporter =
            ProcessReport.buildScanReporter(
                TestSpecs.GRADLE,
                providers.provider { FakeJdkTools.jStatOutput(listOf(FakeJdkTools.G1_DAEMON)) },
                providers.provider { FakeJdkTools.jInfoOutput(listOf(FakeJdkTools.G1_DAEMON)) },
                providers.provider<Boolean> { error("must not be read") },
            )

        assertEquals(IGP_G1_SCAN_VALUES, record(reporter::report).map { "${it.first}=${it.second}" })
    }

    // ---------------------------------------------------------------- spec and value sources

    @Test
    fun `value-source commands are byte-identical to commandline-value-source 0_1_0`() {
        // Literals copied from commandline-value-source v.0.1.0 ProjectExtensions.kt (and its published jar).
        assertEquals(
            "jps | grep GradleDaemon | sed 's/GradleDaemon//' | while read ln; do  jstat -gc -t \$ln; echo \"\$ln\"; done",
            JdkToolCommands.jStat("GradleDaemon"),
        )
        assertEquals(
            "jps | grep KotlinCompileDaemon | sed 's/KotlinCompileDaemon//' | while read ln; do  " +
                "jinfo \$ln  | grep \"XX:MaxHeapSize\"; echo \"\$ln\";  done",
            JdkToolCommands.jInfo("KotlinCompileDaemon"),
        )
    }

    @Test
    fun `a process name that is not a plain class name is rejected`() {
        for (name in listOf("", "Gradle Daemon", "x;rm", "\$HOME", "a|b")) {
            assertFailsWith<IllegalArgumentException>(name) {
                ProcessReportSpec(name, TypeProcess.Gradle, "Gradle processes", "Gradle-Process", null)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun collect(spec: ProcessReportSpec, vararg daemons: FakeDaemon): List<Process> =
        ProcessReport.collect(spec, FakeJdkTools.jStatOutput(daemons.toList()), FakeJdkTools.jInfoOutput(daemons.toList()))

    private fun legacy(spec: ProcessReportSpec, vararg daemons: FakeDaemon): List<String> =
        values(spec, collect(spec, *daemons), gbosEnabled = false)

    private fun values(spec: ProcessReportSpec, processes: List<Process>, gbosEnabled: Boolean): List<String> =
        record { ProcessReport.writeBuildScanValues(spec, processes, gbosEnabled, it) }.map { "${it.first}=${it.second}" }

    private fun gbos(vararg daemons: FakeDaemon): List<Pair<String, String>> =
        record { ProcessReport.writeBuildScanValues(TestSpecs.KOTLIN, collect(TestSpecs.KOTLIN, *daemons), true, it) }

    private fun record(write: (BuildScanSink) -> Unit): List<Pair<String, String>> {
        val recorded = mutableListOf<Pair<String, String>>()
        write(
            object : BuildScanSink {
                override fun value(key: String, value: String) {
                    recorded += key to value
                }

                override fun tag(name: String) = error("no tags expected, got $name")
            },
        )
        return recorded
    }

    private fun parse(json: String): Any? = normalize(JsonSlurper().parseText(json))

    private fun normalize(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associate { it.key to normalize(it.value) }
            is List<*> -> value.map(::normalize)
            is Number -> BigDecimal(value.toString()).stripTrailingZeros()
            else -> value
        }

    private fun assertG1Observation(json: String) =
        assertObservation(
            json,
            pid = 12345,
            gcName = "G1",
            heapLimitBytes = 4294967296.0,
            heapUsedBytes = 1363652116.0,
            heapCommittedBytes = 2083059139.0,
            gcTimeSeconds = 0.6,
            uptimeSeconds = 1117.8,
        )

    /** InfoKotlinProcess's `CharacterizationTest.assertObservation`: parsed structure and values, not raw JSON. */
    private fun assertObservation(
        json: String,
        pid: Long,
        gcName: String,
        heapLimitBytes: Double,
        heapUsedBytes: Double,
        heapCommittedBytes: Double,
        gcTimeSeconds: Double,
        uptimeSeconds: Double,
    ) {
        @Suppress("UNCHECKED_CAST")
        val observation = JsonSlurper().parseText(json) as Map<String, Any?>
        assertEquals(setOf("scope", "aggregationScope", "attributes", "measurements"), observation.keys, json)
        assertEquals("jvm.process", observation["scope"], json)
        assertEquals("entity", observation["aggregationScope"], json)

        @Suppress("UNCHECKED_CAST")
        val attributes = observation["attributes"] as Map<String, Any?>
        assertEquals(setOf("process.pid", "jvm.process.role", "jvm.gc.name"), attributes.keys, json)
        assertEquals(pid, (attributes["process.pid"] as Number).toLong(), json)
        assertEquals("kotlin-daemon", attributes["jvm.process.role"], json)
        assertEquals(gcName, attributes["jvm.gc.name"], json)

        @Suppress("UNCHECKED_CAST")
        val measurements = observation["measurements"] as List<Map<String, Any?>>
        val expected =
            listOf(
                listOf("jvm.process.memory.heap.limit", heapLimitBytes, "By", "last"),
                listOf("jvm.process.memory.heap.used", heapUsedBytes, "By", "last"),
                listOf("jvm.process.memory.heap.committed", heapCommittedBytes, "By", "last"),
                listOf("jvm.process.gc.time", gcTimeSeconds, "s", "sum"),
                listOf("jvm.process.uptime", uptimeSeconds, "s", "last"),
            )
        assertEquals(expected.size, measurements.size, json)
        expected.zip(measurements).forEach { (measurement, actual) ->
            assertEquals(setOf("name", "value", "unit", "aggregation"), actual.keys, json)
            assertEquals(measurement[0], actual["name"], json)
            assertEquals(measurement[1] as Double, (actual["value"] as Number).toDouble(), 1e-6, json)
            assertEquals(measurement[2], actual["unit"], json)
            assertEquals(measurement[3], actual["aggregation"], json)
        }
        assertTrue(json.startsWith("{\"scope\":\"jvm.process\",\"aggregationScope\":\"entity\",\"attributes\":{\"process.pid\":$pid,"), json)
    }

    companion object {
        // InfoGradleProcess CharacterizationTest (U3) goldens.
        val IGP_G1_SCAN_VALUES =
            listOf(
                "Gradle-Process-12345-max=4.0 GB",
                "Gradle-Process-12345-usage=1.27 GB",
                "Gradle-Process-12345-capacity=1.94 GB",
                "Gradle-Process-12345-uptime=18.63 minutes",
                "Gradle-Process-12345-gcTime=0.01 minutes",
                "Gradle-Process-12345-gcType=-XX:+UseG1GC",
            )

        val IGP_PARALLEL_SCAN_VALUES =
            listOf(
                "Gradle-Process-23456-max=2.0 GB",
                "Gradle-Process-23456-usage=1.0 GB",
                "Gradle-Process-23456-capacity=1.52 GB",
                "Gradle-Process-23456-uptime=60.0 minutes",
                "Gradle-Process-23456-gcTime=2.0 minutes",
                "Gradle-Process-23456-gcType=-XX:+UseParallelGC",
            )

        val IGP_G1_CONSOLE_TABLE =
            """
            ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
            │  Gradle processes                                                                               │
            ├─────────┬──────────┬───────────┬────────────┬────────────────┬────────────────┬─────────────────┤
            │  PID    │  Max     │  Usage    │  Capacity  │  GC Time       │  GC Type       │  Uptime         │
            ├─────────┼──────────┼───────────┼────────────┼────────────────┼────────────────┼─────────────────┤
            │  12345  │  4.0 Gb  │  1.27 Gb  │  1.94 Gb   │  0.01 minutes  │  -XX:+UseG1GC  │  18.63 minutes  │
            └─────────┴──────────┴───────────┴────────────┴────────────────┴────────────────┴─────────────────┘
            """.trimIndent()

        // InfoKotlinProcess CharacterizationTest (U3) goldens.
        val IKP_G1_LEGACY_VALUES =
            listOf(
                "Kotlin-Process-12345-max=4.0 GB",
                "Kotlin-Process-12345-usage=1.27 GB",
                "Kotlin-Process-12345-capacity=1.94 GB",
                "Kotlin-Process-12345-uptime=18.63 minutes",
                "Kotlin-Process-12345-gcTime=0.01 minutes",
                "Kotlin-Process-12345-gcType=-XX:+UseG1GC",
            )

        val IKP_PARALLEL_LEGACY_VALUES =
            listOf(
                "Kotlin-Process-23456-max=2.0 GB",
                "Kotlin-Process-23456-usage=1.0 GB",
                "Kotlin-Process-23456-capacity=1.52 GB",
                "Kotlin-Process-23456-uptime=60.0 minutes",
                "Kotlin-Process-23456-gcTime=2.0 minutes",
                "Kotlin-Process-23456-gcType=-XX:+UseParallelGC",
            )

        // InfoKotlinProcess today emits `.name` before `.version`; the shared core publisher emits `.version` first.
        val GBOS_HEADER_KEYS =
            listOf(
                "gbos.schema",
                "gbos.v1.producer.info_kotlin_process.version",
                "gbos.v1.producer.info_kotlin_process.name",
            )

        val GBOS_HEADER_VALUES = listOf("1.0.0", "0.0.4", "info-kotlin-process")

        const val OBSERVATION_KEY = "gbos.v1.producer.info_kotlin_process.observation"

        val IKP_G1_CONSOLE_TABLE =
            """
            ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
            │  Kotlin processes                                                                               │
            ├─────────┬──────────┬───────────┬────────────┬────────────────┬────────────────┬─────────────────┤
            │  PID    │  Max     │  Usage    │  Capacity  │  GC Time       │  GC Type       │  Uptime         │
            ├─────────┼──────────┼───────────┼────────────┼────────────────┼────────────────┼─────────────────┤
            │  12345  │  4.0 Gb  │  1.27 Gb  │  1.94 Gb   │  0.01 minutes  │  -XX:+UseG1GC  │  18.63 minutes  │
            └─────────┴──────────┴───────────┴────────────┴────────────────┴────────────────┴─────────────────┘
            """.trimIndent()

        val IKP_TWO_DAEMON_CONSOLE_TABLE =
            """
            ┌───────────────────────────────────────────────────────────────────────────────────────────────────────┐
            │  Kotlin processes                                                                                     │
            ├─────────┬──────────┬───────────┬────────────┬────────────────┬──────────────────────┬─────────────────┤
            │  PID    │  Max     │  Usage    │  Capacity  │  GC Time       │  GC Type             │  Uptime         │
            ├─────────┼──────────┼───────────┼────────────┼────────────────┼──────────────────────┼─────────────────┤
            │  12345  │  4.0 Gb  │  1.27 Gb  │  1.94 Gb   │  0.01 minutes  │  -XX:+UseG1GC        │  18.63 minutes  │
            ├─────────┼──────────┼───────────┼────────────┼────────────────┼──────────────────────┼─────────────────┤
            │  23456  │  2.0 Gb  │  1.0 Gb   │  1.52 Gb   │  2.0 minutes   │  -XX:+UseParallelGC  │  60.0 minutes   │
            └─────────┴──────────┴───────────┴────────────┴────────────────┴──────────────────────┴─────────────────┘
            """.trimIndent()
    }
}
