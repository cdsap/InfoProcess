package io.github.cdsap.fixture.dualentry

import io.github.cdsap.pluginsupport.BooleanOptIn
import io.github.cdsap.pluginsupport.BuildScanReporter
import io.github.cdsap.pluginsupport.BuildScanSink
import io.github.cdsap.pluginsupport.ConsoleReportService
import io.github.cdsap.pluginsupport.DevelocityAccess
import io.github.cdsap.pluginsupport.LibrarySkewProbe
import io.github.cdsap.pluginsupport.PluginEntry
import io.github.cdsap.pluginsupport.PluginSupportVersion
import io.github.cdsap.pluginsupport.ReportingPluginSpec
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.build.event.BuildEventsListenerRegistry
import javax.inject.Inject

private const val PLUGIN_ID = "io.github.cdsap.fixture.dual-entry"

/**
 * Fixture shaped like GCReport: one class for the settings and the build-script entry through `PluginEntry`, with
 * the legacy Gradle Enterprise lookup enabled. Built against plugin-support 1.1.0 with range [1.1.0, 2.0.0); right
 * after the guard it calls `LibrarySkewProbe.since110()`, an API only the 1.1.0 artifact has.
 *
 * Gradle properties: `dualEntry.consoleWithDevelocity` (console next to Develocity, like GCReport's
 * `enableConsoleLog`; the `dualEntryFixture { consoleWithDevelocity = ... }` DSL wins), `fixture.echoReporter`
 * (print what the reporter writes), and `dualEntry.skipGuard`, which only exists so the suite can show the
 * `NoSuchMethodError` the guard prevents.
 */
abstract class DualEntryFixturePlugin @Inject constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Any> {
    override fun apply(target: Any) {
        val providers =
            when (target) {
                is Settings -> target.providers
                is Project -> target.providers
                else -> error("$PLUGIN_ID can only be applied to Settings or Project")
            }
        if (!providers.gradleProperty("dualEntry.skipGuard").map { it.toBoolean() }.getOrElse(false)) {
            PluginSupportVersion.requireCompatible(PLUGIN_ID, MIN_LIBRARY_VERSION, MAX_LIBRARY_VERSION)
        }
        val api = LibrarySkewProbe.since110()
        val appliedTo = if (target is Project) "project ${target.path}" else "settings"
        println("DUAL-ENTRY applied to $appliedTo plugin-support=${PluginSupportVersion.loaded} api=$api")
        PluginEntry.apply(target, registry, DualEntrySpec(providers))
    }
}

internal const val MIN_LIBRARY_VERSION = "1.1.0"
internal const val MAX_LIBRARY_VERSION = "2.0.0"

abstract class DualEntryExtension {
    abstract val consoleWithDevelocity: Property<Boolean>
}

abstract class DualEntryConsoleService : ConsoleReportService<DualEntryConsoleService.Params>() {
    interface Params : ConsoleReportService.Parameters {
        val collections: Property<Int>
    }

    override fun report() {
        println("DUAL-ENTRY-CONSOLE collections=${parameters.collections.get()}")
    }
}

class DualEntrySpec(private val providers: ProviderFactory) : ReportingPluginSpec<DualEntryConsoleService.Params>() {
    private var extension: DualEntryExtension? = null

    override val pluginId: String get() = PLUGIN_ID
    override val minLibraryVersion: String get() = MIN_LIBRARY_VERSION
    override val maxLibraryVersionExclusive: String get() = MAX_LIBRARY_VERSION
    override val serviceName: String get() = "dualEntryFixtureService"
    override val consoleServiceType: Class<DualEntryConsoleService> get() = DualEntryConsoleService::class.java
    override val legacyGradleEnterprise: Boolean get() = true

    override fun applyToSettings(settings: Settings) {
        extension = createExtension(settings)
    }

    override fun applyToProject(project: Project) {
        extension = createExtension(project)
    }

    override fun configureConsoleService(rootProject: Project, parameters: DualEntryConsoleService.Params) {
        parameters.collections.set(2)
    }

    override fun consoleAlongsideDevelocity(rootProject: Project): Provider<Boolean> = checkNotNull(extension).consoleWithDevelocity

    override fun buildScanReporter(rootProject: Project, access: DevelocityAccess): BuildScanReporter =
        DualEntryReporter(access.toString(), BooleanOptIn.gradleProperty(providers, "fixture.echoReporter").getOrElse(false))

    private fun createExtension(host: ExtensionAware): DualEntryExtension =
        host.extensions.findByType(DualEntryExtension::class.java)
            ?: host.extensions.create("dualEntryFixture", DualEntryExtension::class.java).also {
                BooleanOptIn.convention(it.consoleWithDevelocity, providers, "dualEntry.consoleWithDevelocity", default = false)
            }
}

class DualEntryReporter(private val access: String, private val echo: Boolean) : BuildScanReporter {
    override fun report(sink: BuildScanSink) {
        val target = if (echo) EchoSink(sink) else sink
        target.value("dualEntry.collections", "2")
        target.value("dualEntry.access", access)
        target.tag("gc")
    }
}

class EchoSink(private val delegate: BuildScanSink) : BuildScanSink {
    override fun value(key: String, value: String) {
        println("REPORTED-VALUE $key=$value")
        delegate.value(key, value)
    }

    override fun tag(name: String) {
        println("REPORTED-TAG $name")
        delegate.tag(name)
    }
}
