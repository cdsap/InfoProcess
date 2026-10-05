package io.github.cdsap.processreport.fixtures

import groovy.json.JsonOutput
import io.github.cdsap.jdk.tools.parser.model.Process
import kotlin.math.roundToLong

/**
 * Verbatim port of InfoKotlinProcess's current `output/GbosDevelocityProjection.values` (Groovy `JsonOutput` encoding,
 * `.name` before `.version`). The library's output is compared against it to pin the exact differences.
 */
object IkpReferenceProjection {
    private const val SCHEMA_VERSION = "1.0.0"
    private const val CONTRACT_VERSION = "0.0.4"
    private const val PRODUCER_NAME = "info-kotlin-process"
    private const val PRODUCER_NAMESPACE = "gbos.v1.producer.info_kotlin_process"
    private const val BYTES_PER_GIB = 1_073_741_824.0
    private const val SECONDS_PER_MINUTE = 60.0

    fun values(processes: List<Process>): List<Pair<String, String>> {
        if (processes.isEmpty()) return emptyList()
        return buildList {
            add("gbos.schema" to SCHEMA_VERSION)
            add("$PRODUCER_NAMESPACE.name" to PRODUCER_NAME)
            add("$PRODUCER_NAMESPACE.version" to CONTRACT_VERSION)
            processes.forEach { add("$PRODUCER_NAMESPACE.observation" to observation(it)) }
        }
    }

    private fun observation(process: Process): String {
        val attributes = linkedMapOf<String, Any>("process.pid" to process.pid.toLong(), "jvm.process.role" to "kotlin-daemon")
        normalizeGcName(process.typeGc)?.let { attributes["jvm.gc.name"] = it }
        val measurements =
            listOf(
                Triple("jvm.process.memory.heap.limit", process.max.toBytes().toDouble(), "By") to "last",
                Triple("jvm.process.memory.heap.used", process.usage.toBytes().toDouble(), "By") to "last",
                Triple("jvm.process.memory.heap.committed", process.capacity.toBytes().toDouble(), "By") to "last",
                Triple("jvm.process.gc.time", process.gcTime * SECONDS_PER_MINUTE, "s") to "sum",
                Triple("jvm.process.uptime", process.uptime * SECONDS_PER_MINUTE, "s") to "last",
            )
        return JsonOutput.toJson(
            linkedMapOf(
                "scope" to "jvm.process",
                "aggregationScope" to "entity",
                "attributes" to attributes,
                "measurements" to
                    measurements.map { (measurement, aggregation) ->
                        val (name, value, unit) = measurement
                        linkedMapOf(
                            "name" to name,
                            "value" to if (unit == "By") value.toLong() else value,
                            "unit" to unit,
                            "aggregation" to aggregation,
                        )
                    },
            ),
        )
    }

    private fun Double.toBytes(): Long = (this * BYTES_PER_GIB).roundToLong()

    private fun normalizeGcName(rawName: String): String? =
        when {
            rawName.contains("ZGC", ignoreCase = true) -> "ZGC"
            rawName.contains("Shenandoah", ignoreCase = true) -> "Shenandoah"
            rawName.contains("G1", ignoreCase = true) -> "G1"
            rawName.contains("Parallel", ignoreCase = true) -> "Parallel"
            rawName.contains("Serial", ignoreCase = true) -> "Serial"
            rawName.isNotBlank() && !rawName.startsWith("-XX:") -> rawName
            else -> null
        }
}
