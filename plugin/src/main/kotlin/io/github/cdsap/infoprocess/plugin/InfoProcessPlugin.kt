package io.github.cdsap.infoprocess.plugin

import org.gradle.api.Plugin
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.initialization.Settings

class InfoProcessPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("infoProcess", InfoProcessExtension::class.java)
        configureConventions(settings, extension)
        rejectLegacyPlugins(settings)
        registerService(settings, extension)
    }

    private fun configureConventions(settings: Settings, extension: InfoProcessExtension) {
        val properties = object : ProviderFactoryAccess {
            override fun value(name: String): String? = settings.providers.gradleProperty(name).orNull
        }
        extension.enabled.conventionFromBooleanProperty("infoProcess.enabled", properties, true)
        extension.gradleProcess.enabled.conventionFromBooleanProperty("infoProcess.gradle.enabled", properties, true)
        extension.kotlinProcess.enabled.conventionFromBooleanProperty("infoProcess.kotlin.enabled", properties, true)
        extension.testProcess.enabled.conventionFromBooleanProperty("infoProcess.test.enabled", properties, true)
        extension.testProcess.maxWorkersPublishedToDevelocity.convention(50)
        extension.gcReport.enabled.conventionFromBooleanProperty("infoProcess.gc.enabled", properties, true)
        extension.gcReport.histogram.enabled.convention(false)
        extension.gcReport.histogram.bucket.convention(GcHistogramBucket.FREEDMAN_DIACONIS)
        extension.reporting.console.enabled.convention(true)
        extension.reporting.develocity.enabled.convention(false)
        extension.reporting.gbos.develocity.enabled.convention(false)
        extension.reporting.gbos.json.enabled.convention(false)
        extension.reporting.gbos.ndjson.enabled.convention(false)
        extension.reporting.outputDirectory.convention(settings.layout.settingsDirectory.dir("build/reports/info-process"))
    }

    private fun registerService(settings: Settings, extension: InfoProcessExtension) {
        val registration = settings.gradle.sharedServices.registerIfAbsent("infoProcess", InfoProcessBuildService::class.java) {
            parameters {
                enabled.set(extension.enabled)
                gradleEnabled.set(extension.gradleProcess.enabled)
                kotlinEnabled.set(extension.kotlinProcess.enabled)
                testEnabled.set(extension.testProcess.enabled)
                outputDirectory.set(extension.reporting.outputDirectory)
                gcEnabled.set(extension.enabled.zip(extension.gcReport.enabled) { all, gc -> all && gc })
                gcLogs.set(extension.gcReport.logs.files.map { it.absolutePath })
            }
            maxParallelUsages.set(1)
        }
        registration.get()
    }

    private fun rejectLegacyPlugins(settings: Settings) {
        val legacyIds = setOf(
            "io.github.cdsap.gcreport", "io.github.cdsap.gcreport.project",
            "io.github.cdsap.gradleprocess", "io.github.cdsap.gradleprocess.project",
            "io.github.cdsap.kotlinprocess", "io.github.cdsap.testprocess",
        )
        fun reject(id: String) {
            throw org.gradle.api.GradleException("InfoProcess cannot be used with legacy component plugin '$id'; remove the legacy plugin before applying io.github.cdsap.infoprocess")
        }
        legacyIds.forEach { id -> settings.pluginManager.withPlugin(id) { reject(id) } }
        settings.gradle.beforeProject(Action<Project> {
            legacyIds.forEach { id -> pluginManager.withPlugin(id) { reject(id) } }
        })
    }
}
