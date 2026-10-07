package io.github.cdsap.processreport.fixtures

import java.io.File

/**
 * Deterministic stand-ins for `jps`, `jstat` and `jinfo`, adapted from the InfoGradleProcess and InfoKotlinProcess
 * characterization fixtures. Prepending [install]'s directory to a TestKit build's `PATH` feeds fixed output through
 * the real value-source pipelines, so neither the TestKit daemon nor the host JDK leaks into the results.
 */
data class FakeDaemon(
    val pid: String,
    val jStatHeader: String,
    val jStatData: String,
    val jInfoFlags: String,
)

object FakeJdkTools {
    const val JSTAT_HEADER =
        "Timestamp         S0C         S1C         S0U         S1U          EC          EU          OC          OU" +
            "          MC          MU        CCSC       CCSU     YGC     YGCT     FGC    FGCT     CGC    CGCT       GCT"

    val G1_DAEMON =
        FakeDaemon(
            pid = "12345",
            jStatHeader = JSTAT_HEADER,
            jStatData =
                "        1117.8          0.0      30720.0          0.0      30720.0    1135616.0     755712.0" +
                    "     865280.0     546816.0     195184.0    189433.3      22208.0    20357.8     22    0.682" +
                    "     0    0.000      12    0.070     0.752",
            jInfoFlags =
                "-XX:CICompilerCount=4 -XX:InitialHeapSize=268435456 -XX:MaxHeapSize=4294967296 " +
                    "-XX:+UseCompressedOops -XX:+UseG1GC",
        )

    val PARALLEL_DAEMON =
        FakeDaemon(
            pid = "23456",
            jStatHeader = JSTAT_HEADER,
            jStatData =
                "        3600.0      10240.0      10240.0          0.0       5120.0     524288.0     262144.0" +
                    "    1048576.0     786432.0     131072.0    129024.0      16384.0    15360.0     40    1.500" +
                    "     2    0.300       0    0.000    120.000",
            jInfoFlags = "-XX:MaxHeapSize=2147483648 -XX:+UseParallelGC",
        )

    /** Raw `jStat` value-source output for [daemons], as produced by the shell pipeline. */
    fun jStatOutput(daemons: List<FakeDaemon>): String = daemons.joinToString(separator = "") { "${it.jStatHeader}\n${it.jStatData}\n${it.pid}\n" }

    /** Raw `jInfo` value-source output for [daemons], as produced by the shell pipeline. */
    fun jInfoOutput(daemons: List<FakeDaemon>): String = daemons.joinToString(separator = "") { "${it.jInfoFlags}\n${it.pid}\n" }

    /**
     * Writes executable `jps`, `jstat` and `jinfo` scripts into [binDir] and returns it. `jps` lists [daemons] under
     * [processName], plus [otherProcessName] and `Jps`, which the pipelines must ignore.
     */
    fun install(binDir: File, processName: String, otherProcessName: String, daemons: List<FakeDaemon>): File {
        binDir.mkdirs()
        script(
            File(binDir, "jps"),
            buildString {
                appendLine("echo '4242 $otherProcessName'")
                daemons.forEach { appendLine("echo '${it.pid} $processName'") }
                appendLine("echo '777 Jps'")
            },
        )
        script(File(binDir, "jstat"), logCall("jstat \$3") + caseOnPid("\$3", daemons) { "${it.jStatHeader}\n${it.jStatData}" })
        script(File(binDir, "jinfo"), logCall("jinfo \$1") + caseOnPid("\$1", daemons) { "VM Flags:\n${it.jInfoFlags}" })
        return binDir
    }

    /** The `jstat <pid>` and `jinfo <pid>` calls made through [binDir], in order. */
    fun calls(binDir: File): List<String> = File(binDir, CALLS_LOG).takeIf { it.isFile }?.readLines().orEmpty()

    private const val CALLS_LOG = "calls.log"

    private fun logCall(call: String) = "echo \"$call\" >> \"\$(dirname \"\$0\")/$CALLS_LOG\"\n"

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
