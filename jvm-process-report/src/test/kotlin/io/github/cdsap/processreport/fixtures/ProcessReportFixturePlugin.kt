package io.github.cdsap.processreport.fixtures

import io.github.cdsap.pluginsupport.BuildScanReporter
import io.github.cdsap.pluginsupport.BuildScanSink
import io.github.cdsap.pluginsupport.DevelocityAccess
import io.github.cdsap.pluginsupport.PluginEntry
import io.github.cdsap.pluginsupport.PluginSupportVersion
import io.github.cdsap.pluginsupport.ReportingPluginSpec
import io.github.cdsap.processreport.JdkToolCommands
import io.github.cdsap.processreport.ProcessConsoleService
import io.github.cdsap.processreport.ProcessReport
import io.github.cdsap.processreport.ProcessReportSpec
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.util.GradleVersion
import javax.inject.Inject

/**
 * Test plugin shaped like the migrated InfoGradleProcess / InfoKotlinProcess: the version guard, then `PluginEntry`
 * with a spec whose console service and reporter come from jvm-process-report.
 *
 * Gradle properties: `processReportFixture.spec` (`gradle` or `kotlin`), `processReportFixture.gbos` (GBOS opt-in).
 * It also registers two probe tasks on the root project:
 * - `probeValueSources` prints `PROBE <name>=[<escaped output>]` for the spec's jstat/jinfo value sources and for an
 *   empty and a failing command.
 * - `probeBuildScan` runs the Build Scan reporter against a sink printing `SCAN-VALUE <key>=<value>`, without
 *   Develocity.
 */
abstract class ProcessReportFixturePlugin
    @Inject
    constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Any> {
        override fun apply(target: Any) {
            PluginSupportVersion.requireCompatible(FIXTURE_ID, "0.1.0", "1.0.0")
            println("PROCESS-FIXTURE runtime gradle=${GradleVersion.current().version} kotlin-stdlib=${KotlinVersion.CURRENT}")
            val providers =
                when (target) {
                    is Settings -> target.providers
                    is Project -> target.providers
                    else -> error("unexpected target $target")
                }
            PluginEntry.apply(target, registry, ProcessReportFixtureSpec(providers))
        }
    }

private const val FIXTURE_ID = "io.github.cdsap.processreport.fixture"

class ProcessReportFixtureSpec(private val providers: ProviderFactory) : ReportingPluginSpec<ProcessConsoleService.Params>() {
    private val spec: ProcessReportSpec get() = TestSpecs.byName(providers.gradleProperty("processReportFixture.spec").getOrElse("gradle"))

    override val pluginId: String get() = FIXTURE_ID
    override val minLibraryVersion: String get() = "0.1.0"
    override val maxLibraryVersionExclusive: String get() = "1.0.0"
    override val serviceName: String get() = "processReportFixtureService"
    override val consoleServiceType: Class<ProcessConsoleService> get() = ProcessConsoleService::class.java

    override fun configureConsoleService(rootProject: Project, parameters: ProcessConsoleService.Params) {
        val spec = spec
        parameters.spec.set(spec)
        parameters.jStat.set(ProcessReport.jStat(rootProject.providers, spec))
        parameters.jInfo.set(ProcessReport.jInfo(rootProject.providers, spec))
        registerProbes(rootProject, spec)
    }

    override fun consoleAlongsideDevelocity(rootProject: Project): Provider<Boolean> = rootProject.providers.provider { false }

    override fun buildScanReporter(rootProject: Project, access: DevelocityAccess): BuildScanReporter = reporter(rootProject, spec)

    private fun reporter(rootProject: Project, spec: ProcessReportSpec): BuildScanReporter =
        ProcessReport.buildScanReporter(
            spec,
            ProcessReport.jStat(rootProject.providers, spec),
            ProcessReport.jInfo(rootProject.providers, spec),
            rootProject.providers.gradleProperty("processReportFixture.gbos").map(String::toBoolean).orElse(false),
        )

    private fun registerProbes(rootProject: Project, spec: ProcessReportSpec) {
        val providers = rootProject.providers
        rootProject.tasks.register("probeValueSources", ProbeValueSourcesTask::class.java) { task ->
            task.jStat.set(ProcessReport.jStat(providers, spec))
            task.jInfo.set(ProcessReport.jInfo(providers, spec))
            task.emptyCommand.set(JdkToolCommands.provider(providers, ""))
            task.failingCommand.set(JdkToolCommands.provider(providers, "echo partial; exit 3"))
        }
        rootProject.tasks.register("probeBuildScan", ProbeBuildScanTask::class.java) { task ->
            task.reporter.set(reporter(rootProject, spec))
        }
    }
}

abstract class ProbeValueSourcesTask : DefaultTask() {
    @get:Internal abstract val jStat: Property<String>

    @get:Internal abstract val jInfo: Property<String>

    @get:Internal abstract val emptyCommand: Property<String>

    @get:Internal abstract val failingCommand: Property<String>

    @TaskAction
    fun probe() {
        println("PROBE jstat=[${jStat.get().escaped()}]")
        println("PROBE jinfo=[${jInfo.get().escaped()}]")
        println("PROBE empty=[${emptyCommand.get().escaped()}]")
        println("PROBE failing=[${failingCommand.get().escaped()}]")
    }

    private fun String.escaped() = replace("\n", "\\n")
}

abstract class ProbeBuildScanTask : DefaultTask() {
    @get:Internal abstract val reporter: Property<BuildScanReporter>

    @TaskAction
    fun probe() {
        reporter.get().report(
            object : BuildScanSink {
                override fun value(key: String, value: String) {
                    println("SCAN-VALUE $key=$value")
                }

                override fun tag(name: String) {
                    println("SCAN-TAG $name")
                }
            },
        )
    }
}
