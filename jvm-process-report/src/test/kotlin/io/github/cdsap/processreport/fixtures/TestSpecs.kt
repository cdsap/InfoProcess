package io.github.cdsap.processreport.fixtures

import io.github.cdsap.jdk.tools.parser.model.TypeProcess
import io.github.cdsap.processreport.GbosProducerSpec
import io.github.cdsap.processreport.ProcessReportSpec

/** The specs InfoGradleProcess and InfoKotlinProcess will declare, with the values they emit today. */
object TestSpecs {
    val GRADLE =
        ProcessReportSpec(
            processName = "GradleDaemon",
            typeProcess = TypeProcess.Gradle,
            consoleTitle = "Gradle processes",
            keyPrefix = "Gradle-Process",
            gbos = null,
        )

    val KOTLIN =
        ProcessReportSpec(
            processName = "KotlinCompileDaemon",
            typeProcess = TypeProcess.Kotlin,
            consoleTitle = "Kotlin processes",
            keyPrefix = "Kotlin-Process",
            gbos =
                GbosProducerSpec(
                    schemaVersion = "1.0.0",
                    producerName = "info-kotlin-process",
                    producerVersion = "0.0.4",
                    processRole = "kotlin-daemon",
                ),
        )

    fun byName(name: String): ProcessReportSpec =
        when (name) {
            "gradle" -> GRADLE
            "kotlin" -> KOTLIN
            else -> error("unknown spec '$name'")
        }
}
