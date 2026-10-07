package io.github.cdsap.infoprocess.integration

import java.io.File

/** One JVM daemon as listed by the fake `jps`, with the `jstat -gc -t` data row and `jinfo` flags it reports. */
internal data class FakeDaemon(
    val pid: String,
    val processName: String,
    val jStatData: String,
    val jInfoFlags: String,
)

/**
 * Deterministic stand-ins for `jps`, `jstat` and `jinfo`, adapted from jvm-process-report's test fixtures. Prepending
 * [install]'s directory to a TestKit build's `PATH` feeds fixed output through the real value-source pipelines of the
 * Gradle-spec and Kotlin-spec fixtures, so neither the TestKit daemon nor the host JDK leaks into the results.
 */
internal object FakeJdkTools {
    private const val JSTAT_HEADER =
        "Timestamp         S0C         S1C         S0U         S1U          EC          EU          OC          OU" +
            "          MC          MU        CCSC       CCSU     YGC     YGCT     FGC    FGCT     CGC    CGCT       GCT"

    val GRADLE_DAEMON =
        FakeDaemon(
            pid = "12345",
            processName = "GradleDaemon",
            jStatData =
                "        1117.8          0.0      30720.0          0.0      30720.0    1135616.0     755712.0" +
                    "     865280.0     546816.0     195184.0    189433.3      22208.0    20357.8     22    0.682" +
                    "     0    0.000      12    0.070     0.752",
            jInfoFlags =
                "-XX:CICompilerCount=4 -XX:InitialHeapSize=268435456 -XX:MaxHeapSize=4294967296 " +
                    "-XX:+UseCompressedOops -XX:+UseG1GC",
        )

    val KOTLIN_DAEMON =
        FakeDaemon(
            pid = "23456",
            processName = "KotlinCompileDaemon",
            jStatData =
                "        3600.0      10240.0      10240.0          0.0       5120.0     524288.0     262144.0" +
                    "    1048576.0     786432.0     131072.0    129024.0      16384.0    15360.0     40    1.500" +
                    "     2    0.300       0    0.000    120.000",
            jInfoFlags = "-XX:MaxHeapSize=2147483648 -XX:+UseParallelGC",
        )

    /** Writes executable `jps`, `jstat` and `jinfo` scripts for [daemons] (plus two unrelated processes) into [binDir]. */
    fun install(binDir: File, daemons: List<FakeDaemon>): File {
        binDir.mkdirs()
        script(
            File(binDir, "jps"),
            buildString {
                appendLine("echo '4242 Launcher'")
                daemons.forEach { appendLine("echo '${it.pid} ${it.processName}'") }
                appendLine("echo '777 Jps'")
            },
        )
        script(File(binDir, "jstat"), caseOnPid("\$3", daemons) { "$JSTAT_HEADER\n${it.jStatData}" })
        script(File(binDir, "jinfo"), caseOnPid("\$1", daemons) { "VM Flags:\n${it.jInfoFlags}" })
        return binDir
    }

    private fun caseOnPid(pidArgument: String, daemons: List<FakeDaemon>, output: (FakeDaemon) -> String): String =
        buildString {
            appendLine("case \"$pidArgument\" in")
            daemons.forEach {
                appendLine("  ${it.pid})")
                appendLine("    cat <<'EOF'")
                appendLine(output(it))
                appendLine("EOF")
                appendLine("    ;;")
            }
            appendLine("esac")
        }

    private fun script(file: File, body: String) {
        file.writeText("#!/bin/sh\n$body")
        check(file.setExecutable(true)) { "Could not make $file executable" }
    }
}
