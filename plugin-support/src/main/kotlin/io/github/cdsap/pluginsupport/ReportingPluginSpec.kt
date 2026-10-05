package io.github.cdsap.pluginsupport

import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider

/**
 * What a plugin tells [PluginEntry]. Subclass it in the plugin; new members are only ever added as open members
 * with a default, so plugins compiled against an older library keep working.
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

    /** Runs on every settings application, before configure-once; create the settings DSL here. */
    public open fun applyToSettings(settings: Settings) {}

    /** Runs on every project application, before configure-once; create the project DSL here. */
    public open fun applyToProject(project: Project) {}

    /** Configures the console service parameters, once per build, from the root project. */
    public abstract fun configureConsoleService(rootProject: Project, parameters: P)

    /**
     * The plugin's console rule when Develocity was found through [DevelocityAccess.DEVELOCITY]: whether the console
     * report still prints next to the Build Scan values. Evaluated at the end of the build, so DSL values set after
     * the plugin was applied count. Without Develocity, or when it was found through a path whose
     * [DevelocityAccess.keepsConsole] is true, the console report always prints.
     */
    public abstract fun consoleAlongsideDevelocity(rootProject: Project): Provider<Boolean>

    /**
     * Returns the reporter that writes the plugin's Build Scan data when Develocity was found, or null to write
     * nothing. Called once per build, from the root project.
     */
    public abstract fun buildScanReporter(rootProject: Project, access: DevelocityAccess): BuildScanReporter?
}
