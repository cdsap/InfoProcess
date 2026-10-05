package io.github.cdsap.pluginsupport.fixture

import io.github.cdsap.pluginsupport.BooleanOptIn
import io.github.cdsap.pluginsupport.BuildScanReporter
import io.github.cdsap.pluginsupport.ConsoleReportService
import io.github.cdsap.pluginsupport.DevelocityAccess
import io.github.cdsap.pluginsupport.PluginApplication
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

/**
 * Test plugin built on plugin-support the way the cdsap plugins use it: `io.github.cdsap.pluginsupport.fixture`
 * accepts settings or a project, `io.github.cdsap.pluginsupport.fixture.project` is a project-only alias sharing the
 * service name. Every hook prints a `FIXTURE` line so TestKit tests can count invocations.
 *
 * Gradle properties: `fixture.value` (reported value), `fixture.minLibraryVersion` (guard range),
 * `fixture.legacyGradleEnterprise` (legacy lookup opt-in), `fixture.consoleWithDevelocity` (console rule; DSL
 * `fixtureReport { consoleWithDevelocity = ... }` wins), `fixture.subscribe` (task completion subscription),
 * `fixture.taskUsesService` (registers `usesFixtureService`, a task that declares the console service).
 */
abstract class FixturePlugin
    @Inject
    constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Any> {
        override fun apply(target: Any) {
            val providers =
                when (target) {
                    is Settings -> target.providers
                    is Project -> target.providers
                    else -> error("unexpected target $target")
                }
            requireFixtureCompatible(providers)
            PluginEntry.apply(target, registry, FixtureSpec(providers))
        }
    }

abstract class FixtureProjectPlugin
    @Inject
    constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Project> {
        override fun apply(target: Project) {
            requireFixtureCompatible(target.providers)
            PluginEntry.apply(target, registry, FixtureSpec(target.providers))
        }
    }

private const val FIXTURE_ID = "io.github.cdsap.pluginsupport.fixture"
private const val FIXTURE_MAX_LIBRARY_VERSION = "1.0.0"

private fun fixtureMinLibraryVersion(providers: ProviderFactory): String =
    providers.gradleProperty("fixture.minLibraryVersion").getOrElse("0.1.0")

private fun requireFixtureCompatible(providers: ProviderFactory) =
    PluginSupportVersion.requireCompatible(FIXTURE_ID, fixtureMinLibraryVersion(providers), FIXTURE_MAX_LIBRARY_VERSION)

abstract class FixtureExtension {
    abstract val consoleWithDevelocity: Property<Boolean>
}

abstract class FixtureConsoleService : ConsoleReportService<FixtureConsoleService.Params>() {
    interface Params : ConsoleReportService.Parameters {
        val value: Property<String>
    }

    override fun report() {
        println("FIXTURE-CONSOLE value=${parameters.value.get()}")
    }
}

class FixtureSpec(private val providers: ProviderFactory) : ReportingPluginSpec<FixtureConsoleService.Params>() {
    private var extension: FixtureExtension? = null

    override val pluginId: String get() = FIXTURE_ID
    override val minLibraryVersion: String get() = fixtureMinLibraryVersion(providers)
    override val maxLibraryVersionExclusive: String get() = FIXTURE_MAX_LIBRARY_VERSION
    override val serviceName: String get() = "fixtureReportService"
    override val consoleServiceType: Class<FixtureConsoleService> get() = FixtureConsoleService::class.java
    override val legacyGradleEnterprise: Boolean
        get() = BooleanOptIn.gradleProperty(providers, "fixture.legacyGradleEnterprise").getOrElse(false)
    override val subscribeToTaskCompletion: Boolean
        get() = BooleanOptIn.gradleProperty(providers, "fixture.subscribe").getOrElse(true)

    private var develocityPresent: Provider<Boolean>? = null

    override fun applyToSettings(settings: Settings, develocityPresent: Provider<Boolean>) {
        println("FIXTURE applied to settings daemon=${ProcessHandle.current().pid()} present-at-apply=${develocityPresent.get()}")
        this.develocityPresent = develocityPresent
        extension = createExtension(settings)
    }

    override fun applyToProject(project: Project) {
        println("FIXTURE applied to project ${project.path} daemon=${ProcessHandle.current().pid()}")
        extension = createExtension(project)
    }

    override fun configureConsoleService(
        rootProject: Project,
        parameters: FixtureConsoleService.Params,
        develocity: DevelocityAccess?,
    ) {
        println("FIXTURE configured from '${rootProject.path}'")
        println("FIXTURE configure access=$develocity")
        parameters.value.set(reportedValue())
    }

    override fun consoleAlongsideDevelocity(rootProject: Project, application: PluginApplication): Provider<Boolean> {
        println("FIXTURE console rule application=$application")
        return checkNotNull(extension).consoleWithDevelocity
    }

    override fun buildScanReporter(rootProject: Project, access: DevelocityAccess): BuildScanReporter {
        println("FIXTURE reporter registered access=$access")
        return reporter(reportedValue(), access.toString())
    }

    override fun onConfigured(
        rootProject: Project,
        service: Provider<out ConsoleReportService<FixtureConsoleService.Params>>,
        develocity: DevelocityAccess?,
    ) {
        println("FIXTURE onConfigured access=$develocity present=${develocityPresent?.get()}")
        if (BooleanOptIn.gradleProperty(providers, "fixture.taskUsesService").getOrElse(false)) {
            rootProject.tasks.register("usesFixtureService") { task ->
                task.usesService(service)
                task.doLast {
                    service.get()
                    println("FIXTURE task ran")
                }
            }
        }
    }

    private fun reportedValue(): Provider<String> = providers.gradleProperty("fixture.value").orElse("default-value")

    private fun createExtension(host: ExtensionAware): FixtureExtension =
        host.extensions.findByType(FixtureExtension::class.java)
            ?: host.extensions.create("fixtureReport", FixtureExtension::class.java).also {
                BooleanOptIn.convention(it.consoleWithDevelocity, providers, "fixture.consoleWithDevelocity", default = false)
            }

    private companion object {
        // A lambda, as plugins will write it; it captures only a provider and a string.
        fun reporter(value: Provider<String>, access: String) =
            BuildScanReporter { sink ->
                sink.value("fixture.value", value.get())
                sink.value("fixture.access", access)
                sink.tag("fixture")
            }
    }
}
