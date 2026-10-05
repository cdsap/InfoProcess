package io.github.cdsap.pluginsupport

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.build.event.BuildEventsListenerRegistry

/**
 * Entry dispatcher shared by the cdsap plugins: one implementation class serves the settings id and the
 * build-script ids (and their `.project` aliases).
 *
 * ```
 * abstract class GCReportPlugin @Inject constructor(
 *     private val registry: BuildEventsListenerRegistry,
 * ) : Plugin<Any> {
 *     override fun apply(target: Any) {
 *         PluginSupportVersion.requireCompatible("io.github.cdsap.gcreport", "1.0.0", "2.0.0")
 *         PluginEntry.apply(target, registry, GCReportSpec())
 *     }
 * }
 *
 * class GCReportSpec : ReportingPluginSpec<GCReportService.Params>() {
 *     override val pluginId = "io.github.cdsap.gcreport"
 *     override val minLibraryVersion = "1.0.0"          // the plugin-support version compiled against
 *     override val maxLibraryVersionExclusive = "2.0.0"
 *     override val serviceName = "gcReportService"
 *     override val consoleServiceType = GCReportService::class.java
 *     override val legacyGradleEnterprise = true
 *     override fun applyToSettings(settings: Settings) { createExtension(settings) }
 *     override fun applyToProject(project: Project) { createExtension(project) }
 *     override fun configureConsoleService(rootProject: Project, parameters: GCReportService.Params) { ... }
 *     override fun consoleAlongsideDevelocity(rootProject: Project) = extension.enableConsoleLog
 *     override fun buildScanReporter(rootProject: Project, access: DevelocityAccess) =
 *         GCReportScanReporter(extension.logs, extension.histogramEnabled)
 * }
 * ```
 *
 * Call [PluginSupportVersion.requireCompatible] yourself as the first line of `apply`, before constructing the
 * spec: [ReportingPluginSpec] is a library class, so the check must not depend on it linking against the loaded
 * library version. [apply] repeats the check, which is cheap.
 *
 * On each application [apply]:
 * 1. Runs [PluginSupportVersion.requireCompatible] before anything else.
 * 2. Calls [ReportingPluginSpec.applyToSettings] or [ReportingPluginSpec.applyToProject].
 * 3. Configures once per build, from the root project: from `gradle.rootProject {}` registered from settings (no
 *    `gradle.lifecycle`, which needs Gradle 8.8), or from the root project when applied to any project.
 *
 * Configuring registers the console service under [ReportingPluginSpec.serviceName] and, unless
 * [ReportingPluginSpec.subscribeToTaskCompletion] is false, subscribes it to the injected
 * [BuildEventsListenerRegistry]; when the registration already exists nothing else happens, so the Build Scan
 * reporter and console are wired once whichever path ran first. There is no static state, so every build in a daemon
 * configures again. When Develocity is found the reporter from [ReportingPluginSpec.buildScanReporter] runs from
 * `buildScan.buildFinished`; the console prints unless Develocity was found through [DevelocityAccess.DEVELOCITY]
 * and [ReportingPluginSpec.consoleAlongsideDevelocity] is false. [ReportingPluginSpec] lists the hook order.
 */
public object PluginEntry {
    public fun <P : ConsoleReportService.Parameters> apply(
        target: Any,
        registry: BuildEventsListenerRegistry,
        spec: ReportingPluginSpec<P>,
    ) {
        PluginSupportVersion.requireCompatible(spec.pluginId, spec.minLibraryVersion, spec.maxLibraryVersionExclusive)
        when (target) {
            is Settings -> {
                val detection = DetectionResult()
                spec.applyToSettings(target, target.providers.provider { detection.present })
                DevelocityBridge.detect(target, spec.legacyGradleEnterprise) { rootProject, develocity ->
                    detection.present = develocity != null
                    configureOnce(rootProject, registry, spec, develocity, PluginApplication.SETTINGS)
                }
            }
            is Project -> {
                spec.applyToProject(target)
                val application =
                    if (target.path == Project.PATH_SEPARATOR) PluginApplication.ROOT_PROJECT else PluginApplication.SUBPROJECT
                target.gradle.rootProject(
                    Action { rootProject ->
                        val develocity = DevelocityBridge.detect(rootProject, spec.legacyGradleEnterprise)
                        configureOnce(rootProject, registry, spec, develocity, application)
                    },
                )
            }
            else -> throw GradleException(
                "${spec.pluginId} can only be applied to Settings or Project, not ${target.javaClass.name}",
            )
        }
    }

    private fun <P : ConsoleReportService.Parameters> configureOnce(
        rootProject: Project,
        registry: BuildEventsListenerRegistry,
        spec: ReportingPluginSpec<P>,
        develocity: DetectedDevelocity?,
        application: PluginApplication,
    ) {
        @Suppress("UNCHECKED_CAST")
        val serviceType = spec.consoleServiceType as Class<ConsoleReportService<P>>
        var claimed = false
        val service =
            rootProject.gradle.sharedServices.registerIfAbsent(spec.serviceName, serviceType) { serviceSpec ->
                claimed = true
                spec.configureConsoleService(rootProject, serviceSpec.parameters, develocity?.access)
                if (develocity == null || develocity.access.keepsConsole) {
                    serviceSpec.parameters.consoleEnabled.set(true)
                } else {
                    serviceSpec.parameters.consoleEnabled.set(spec.consoleAlongsideDevelocity(rootProject, application))
                }
            }
        if (!claimed) {
            return
        }
        if (spec.subscribeToTaskCompletion) {
            registry.onTaskCompletion(service)
        }
        if (develocity != null) {
            spec.buildScanReporter(rootProject, develocity.access)?.let { develocity.onBuildFinished(it) }
        }
        spec.onConfigured(rootProject, service, develocity?.access)
    }

    private class DetectionResult {
        var present = false
    }
}
