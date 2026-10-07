package io.github.cdsap.pluginsupport

import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider

/**
 * What a plugin tells [PluginEntry]. Subclass it in the plugin; new members are only ever added as open members
 * with a default, so plugins compiled against an older library keep working.
 *
 * Pass a new instance to every [PluginEntry.apply] call. [PluginEntry] calls every hook of one application on the
 * instance passed for it, so a spec may keep state between hooks, for example the extension created in
 * [applyToSettings] and read in [consoleAlongsideDevelocity]. Only the application that configures the build (the
 * first one to reach configure-once) runs the configure hooks; later applications only run [applyToSettings] or
 * [applyToProject].
 *
 * Hook order for one build:
 * 1. [applyToSettings] or [applyToProject], on every application.
 * 2. Once, from the root project: [configureConsoleService] and [consoleAlongsideDevelocity], inside the console
 *    service registration.
 * 3. Then [buildScanReporter], when Develocity was found.
 * 4. Then [onConfigured].
 *
 * @param P the parameters type of the plugin's [ConsoleReportService].
 */
public abstract class ReportingPluginSpec<P : ConsoleReportService.Parameters> {
    /** Id of the plugin, used in error messages. */
    public abstract val pluginId: String

    /** Library version the plugin was compiled against; see [PluginSupportVersion.requireCompatible]. */
    public abstract val minLibraryVersion: String

    /** Next major library version, excluded from the supported range. */
    public abstract val maxLibraryVersionExclusive: String

    /**
     * Registration name of the plugin's console build service. It is also the configure-once key: the first
     * application in a build registers it, later applications (settings id plus `.project` alias, several
     * subprojects) see the registration and do nothing.
     */
    public abstract val serviceName: String

    /** The plugin's console build service class, registered under [serviceName]. */
    public abstract val consoleServiceType: Class<out ConsoleReportService<P>>

    /** Also look for the legacy `com.gradle.enterprise` plugin and its `gradleEnterprise` extension. */
    public open val legacyGradleEnterprise: Boolean get() = false

    /**
     * Whether [PluginEntry] subscribes the console service to task completion events, so Gradle starts it with the
     * first task and closes it at the end of every build. Return false for a service that tasks declare with
     * `usesService` and that must only start when one of them runs; its report then only runs in those builds.
     */
    public open val subscribeToTaskCompletion: Boolean get() = true

    /** Runs on every settings application, before configure-once; create the settings DSL here. */
    public open fun applyToSettings(settings: Settings) {}

    /**
     * Like [applyToSettings], with [develocityPresent]: true when Develocity was found for this build. It is decided
     * after the settings script, from `gradle.rootProject {}`, and reads false before that, so use it as a lazy
     * convention. The default calls [applyToSettings].
     */
    public open fun applyToSettings(settings: Settings, develocityPresent: Provider<Boolean>) {
        applyToSettings(settings)
    }

    /** Runs on every project application, before configure-once; create the project DSL here. */
    public open fun applyToProject(project: Project) {}

    /** Configures the console service parameters, once per build, from the root project. */
    public open fun configureConsoleService(rootProject: Project, parameters: P) {}

    /**
     * Like [configureConsoleService], with how Develocity was found, or null when it was not. The default calls
     * [configureConsoleService].
     */
    public open fun configureConsoleService(rootProject: Project, parameters: P, develocity: DevelocityAccess?) {
        configureConsoleService(rootProject, parameters)
    }

    /**
     * The plugin's console rule when Develocity was found through [DevelocityAccess.DEVELOCITY]: whether the console
     * report still prints next to the Build Scan values. Evaluated at the end of the build, so DSL values set after
     * the plugin was applied count. Without Develocity, or when it was found through a path whose
     * [DevelocityAccess.keepsConsole] is true, the console report always prints. The default is false: the Build
     * Scan values replace the console report.
     */
    public open fun consoleAlongsideDevelocity(rootProject: Project): Provider<Boolean> = rootProject.providers.provider { false }

    /**
     * Like [consoleAlongsideDevelocity], with where the configuring application was applied. Override it when a
     * build-script application used to look for Develocity on the applying project only, so a subproject application
     * never found it and its users only saw the console report. The default calls [consoleAlongsideDevelocity].
     */
    public open fun consoleAlongsideDevelocity(rootProject: Project, application: PluginApplication): Provider<Boolean> =
        consoleAlongsideDevelocity(rootProject)

    /**
     * Returns the reporter that writes the plugin's Build Scan data when Develocity was found, or null to write
     * nothing. Called once per build, from the root project.
     */
    public abstract fun buildScanReporter(rootProject: Project, access: DevelocityAccess): BuildScanReporter?

    /**
     * Runs once per build after the console service is registered and the Build Scan reporter, if any, is set up.
     * [service] is the registration, for `usesService` or wiring tasks; [develocity] is how Develocity was found, or
     * null when it was not.
     */
    public open fun onConfigured(
        rootProject: Project,
        service: Provider<out ConsoleReportService<P>>,
        develocity: DevelocityAccess?,
    ) {}
}
