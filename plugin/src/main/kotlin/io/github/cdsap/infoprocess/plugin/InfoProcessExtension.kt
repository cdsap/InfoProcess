package io.github.cdsap.infoprocess.plugin

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import javax.inject.Inject

abstract class InfoProcessExtension @Inject constructor(objects: ObjectFactory) {
    abstract val enabled: Property<Boolean>
    val gradleProcess: ProcessComponentExtension = objects.newInstance(ProcessComponentExtension::class.java)
    val kotlinProcess: ProcessComponentExtension = objects.newInstance(ProcessComponentExtension::class.java)
    val testProcess: TestProcessExtension = objects.newInstance(TestProcessExtension::class.java)
    val gcReport: GcReportExtension = objects.newInstance(GcReportExtension::class.java)
    val reporting: ReportingExtension = objects.newInstance(ReportingExtension::class.java)

    fun gradleProcess(action: Action<in ProcessComponentExtension>) = action.execute(gradleProcess)
    fun kotlinProcess(action: Action<in ProcessComponentExtension>) = action.execute(kotlinProcess)
    fun testProcess(action: Action<in TestProcessExtension>) = action.execute(testProcess)
    fun gcReport(action: Action<in GcReportExtension>) = action.execute(gcReport)
    fun reporting(action: Action<in ReportingExtension>) = action.execute(reporting)
}

abstract class ProcessComponentExtension {
    @get:Input abstract val enabled: Property<Boolean>
}

abstract class TestProcessExtension {
    @get:Input abstract val enabled: Property<Boolean>
    @get:Input abstract val maxWorkersPublishedToDevelocity: Property<Int>
}

abstract class GcReportExtension @Inject constructor(objects: ObjectFactory) {
    @get:Input abstract val enabled: Property<Boolean>
    abstract val logs: ConfigurableFileCollection
    val histogram: HistogramExtension = objects.newInstance(HistogramExtension::class.java)

    fun histogram(action: Action<in HistogramExtension>) = action.execute(histogram)
}

abstract class HistogramExtension {
    @get:Input abstract val enabled: Property<Boolean>
    @get:Input abstract val bucket: Property<GcHistogramBucket>
}

enum class GcHistogramBucket { FREEDMAN_DIACONIS, SCOTT, STURGES }

abstract class ReportingExtension @Inject constructor(objects: ObjectFactory) {
    abstract val outputDirectory: DirectoryProperty
    val console: ToggleExtension = objects.newInstance(ToggleExtension::class.java)
    val develocity: ToggleExtension = objects.newInstance(ToggleExtension::class.java)
    val gbos: GbosReportingExtension = objects.newInstance(GbosReportingExtension::class.java)

    fun console(action: Action<in ToggleExtension>) = action.execute(console)
    fun develocity(action: Action<in ToggleExtension>) = action.execute(develocity)
    fun gbos(action: Action<in GbosReportingExtension>) = action.execute(gbos)
}

abstract class ToggleExtension { @get:Input abstract val enabled: Property<Boolean> }

abstract class GbosReportingExtension @Inject constructor(objects: ObjectFactory) {
    val develocity: ToggleExtension = objects.newInstance(ToggleExtension::class.java)
    val json: ToggleExtension = objects.newInstance(ToggleExtension::class.java)
    val ndjson: ToggleExtension = objects.newInstance(ToggleExtension::class.java)

    fun develocity(action: Action<in ToggleExtension>) = action.execute(develocity)
    fun json(action: Action<in ToggleExtension>) = action.execute(json)
    fun ndjson(action: Action<in ToggleExtension>) = action.execute(ndjson)
}

