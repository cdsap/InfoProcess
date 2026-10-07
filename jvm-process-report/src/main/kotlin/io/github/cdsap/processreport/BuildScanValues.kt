package io.github.cdsap.processreport

import io.github.cdsap.gbos.core.GbosAttributeValue
import io.github.cdsap.gbos.core.GbosCustomValueSink
import io.github.cdsap.gbos.core.GbosDevelocity
import io.github.cdsap.gbos.core.GbosMeasurement
import io.github.cdsap.gbos.core.GbosObservation
import io.github.cdsap.gbos.core.GbosProducer
import io.github.cdsap.jdk.tools.parser.model.Process
import io.github.cdsap.pluginsupport.BuildScanSink
import kotlin.math.roundToLong

/** The custom values InfoGradleProcess and InfoKotlinProcess have always written: six per daemon, in this order. */
internal object LegacyCustomValues {
    fun write(keyPrefix: String, processes: List<Process>, sink: BuildScanSink) {
        processes.forEach { process ->
            val key = "$keyPrefix-${process.pid}"
            sink.value("$key-max", "${process.max} GB")
            sink.value("$key-usage", "${process.usage} GB")
            sink.value("$key-capacity", "${process.capacity} GB")
            sink.value("$key-uptime", "${process.uptime} minutes")
            sink.value("$key-gcTime", "${process.gcTime} minutes")
            sink.value("$key-gcType", process.typeGc)
        }
    }
}

/** One `jvm.process` observation per daemon, published through build-observability-core's [GbosDevelocity]. */
internal object GbosProjection {
    private const val SCOPE = "jvm.process"
    private const val AGGREGATION_SCOPE = "entity"
    private const val BYTES_PER_GIB = 1_073_741_824.0
    private const val SECONDS_PER_MINUTE = 60.0

    fun write(spec: ProcessReportSpec, gbos: GbosProducerSpec, processes: List<Process>, sink: BuildScanSink) {
        GbosDevelocity.publish(
            GbosCustomValueSink { key, value -> sink.value(key, value) },
            gbos.schemaVersion,
            gbos.producerName,
            gbos.producerVersion,
            processes.map { observation(spec, gbos, it) },
            emptyList(),
        )
    }

    fun observation(spec: ProcessReportSpec, gbos: GbosProducerSpec, process: Process): GbosObservation {
        val attributes = LinkedHashMap<String, GbosAttributeValue>()
        attributes["process.pid"] =
            GbosAttributeValue.Integer(
                requireNotNull(process.pid.toLongOrNull()) { "${spec.processName} PID must be numeric: ${process.pid}" },
            )
        attributes["jvm.process.role"] = GbosAttributeValue.Text(gbos.processRole)
        normalizeGcName(process.typeGc)?.let { attributes["jvm.gc.name"] = GbosAttributeValue.Text(it) }
        return GbosObservation(
            schemaVersion = gbos.schemaVersion,
            producer = GbosProducer(gbos.producerName, gbos.producerVersion),
            scope = SCOPE,
            aggregationScope = AGGREGATION_SCOPE,
            attributes = attributes,
            measurements =
                listOf(
                    GbosMeasurement("jvm.process.memory.heap.limit", process.max.toBytes(), "By", "last"),
                    GbosMeasurement("jvm.process.memory.heap.used", process.usage.toBytes(), "By", "last"),
                    GbosMeasurement("jvm.process.memory.heap.committed", process.capacity.toBytes(), "By", "last"),
                    GbosMeasurement("jvm.process.gc.time", process.gcTime * SECONDS_PER_MINUTE, "s", "sum"),
                    GbosMeasurement("jvm.process.uptime", process.uptime * SECONDS_PER_MINUTE, "s", "last"),
                ),
        )
    }

    // Whole bytes, as InfoKotlinProcess has always reported them.
    private fun Double.toBytes(): Double = (this * BYTES_PER_GIB).roundToLong().toDouble()

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
