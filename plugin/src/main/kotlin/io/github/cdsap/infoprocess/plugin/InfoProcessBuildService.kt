package io.github.cdsap.infoprocess.plugin

import io.github.cdsap.infoprocess.collector.gc.GcLogParser
import io.github.cdsap.infoprocess.core.Component
import io.github.cdsap.infoprocess.core.ComponentReport
import io.github.cdsap.infoprocess.core.ComponentStatus
import io.github.cdsap.infoprocess.core.InfoProcessAggregator
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class InfoProcessBuildService : BuildService<InfoProcessBuildService.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val enabled: org.gradle.api.provider.Property<Boolean>
        val gradleEnabled: org.gradle.api.provider.Property<Boolean>
        val kotlinEnabled: org.gradle.api.provider.Property<Boolean>
        val testEnabled: org.gradle.api.provider.Property<Boolean>
        val outputDirectory: org.gradle.api.file.DirectoryProperty
        val gcEnabled: org.gradle.api.provider.Property<Boolean>
        val gcLogs: org.gradle.api.provider.ListProperty<String>
    }

    private val aggregator = InfoProcessAggregator()

    private fun collectGc() {
        val parser = GcLogParser()
        val reports = parameters.gcLogs.get().map { parser.parse(java.io.File(it)) }
        if (reports.isEmpty()) {
            aggregator.record(Component.GC, ComponentReport(ComponentStatus.NO_DATA))
            return
        }
        aggregator.record(
            Component.GC,
            ComponentReport(
                if (reports.any { !it.missing && it.observations.isNotEmpty() }) ComponentStatus.SUCCESS else ComponentStatus.NO_DATA,
                gcObservations = reports.flatMap { it.observations },
            ),
        )
    }

    override fun close() {
        if (!parameters.enabled.get()) return
        aggregator.record(Component.GRADLE, ComponentReport(if (parameters.gradleEnabled.get()) ComponentStatus.NO_DATA else ComponentStatus.DISABLED))
        aggregator.record(Component.KOTLIN, ComponentReport(if (parameters.kotlinEnabled.get()) ComponentStatus.NO_DATA else ComponentStatus.DISABLED))
        aggregator.record(Component.TEST, ComponentReport(if (parameters.testEnabled.get()) ComponentStatus.NO_DATA else ComponentStatus.DISABLED))
        if (parameters.gcEnabled.get()) collectGc() else aggregator.record(Component.GC, ComponentReport(ComponentStatus.DISABLED))
        val output = parameters.outputDirectory.get().asFile
        output.mkdirs()
        val report = aggregator.snapshot()
        output.resolve("summary.json").writeText(report.toJson())
    }
}

private fun io.github.cdsap.infoprocess.core.InfoProcessReport.toJson(): String {
    fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val components = components.entries.sortedBy { it.key.name }.joinToString(",") { (component, report) ->
        quote(component.name.lowercase()) + ":{" + "\"status\":" + quote(report.status.name.lowercase()) +",\"processes\":" + report.processObservations.size + ",\"gc\":" + report.gcObservations.size + "}"
    }
    return "{\"components\":{$components,\"processCount\":${processes.size},\"gcCount\":${gc.size}}}"
}
