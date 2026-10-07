package io.github.cdsap.fixture.kotlinspec

import io.github.cdsap.jdk.tools.parser.model.TypeProcess
import io.github.cdsap.pluginsupport.BooleanOptIn
import io.github.cdsap.pluginsupport.BuildScanReporter
import io.github.cdsap.pluginsupport.BuildScanSink
import io.github.cdsap.pluginsupport.DevelocityAccess
import io.github.cdsap.pluginsupport.PluginEntry
import io.github.cdsap.pluginsupport.PluginSupportVersion
import io.github.cdsap.pluginsupport.ReportingPluginSpec
import io.github.cdsap.processreport.GbosProducerSpec
import io.github.cdsap.processreport.ProcessConsoleService
import io.github.cdsap.processreport.ProcessReport
import io.github.cdsap.processreport.ProcessReportSpec
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.build.event.BuildEventsListenerRegistry
import javax.inject.Inject

private const val PLUGIN_ID = "io.github.cdsap.fixture.kotlin-spec"

private val KOTLIN_PROCESSES =
    ProcessReportSpec(
        processName = "KotlinCompileDaemon",
        typeProcess = TypeProcess.Kotlin,
        consoleTitle = "Kotlin processes",
        keyPrefix = "Kotlin-Process",
        gbos =
            GbosProducerSpec(
                schemaVersion = "1.0.0",
                producerName = "kotlin-spec-fixture",
                producerVersion = "0.0.4",
                processRole = "kotlin-daemon",
            ),
    )

/**
 * Fixture shaped like the migrated InfoKotlinProcess: the version guard (built against jvm-process-report 1.1.0, range
 * [1.1.0, 2.0.0)), then `PluginEntry` with jvm-process-report's console service and reporter for
 * `KotlinCompileDaemon`. `kotlinSpec.gbos.enabled=true` replaces the legacy values with the GBOS projection.
 * `fixture.echoReporter=true` prints what the reporter writes.
 */
abstract class KotlinSpecFixturePlugin @Inject constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Any> {
    override fun apply(target: Any) {
        PluginSupportVersion.requireCompatible(PLUGIN_ID, "1.1.0", "2.0.0")
        val providers =
            when (target) {
                is Settings -> target.providers
                is Project -> target.providers
                else -> error("$PLUGIN_ID can only be applied to Settings or Project")
            }
        println("KOTLIN-SPEC applied plugin-support=${PluginSupportVersion.loaded}")
        PluginEntry.apply(target, registry, KotlinSpecFixtureSpec(providers))
    }
}

class KotlinSpecFixtureSpec(private val providers: ProviderFactory) : ReportingPluginSpec<ProcessConsoleService.Params>() {
    override val pluginId: String get() = PLUGIN_ID
    override val minLibraryVersion: String get() = "1.1.0"
    override val maxLibraryVersionExclusive: String get() = "2.0.0"
    override val serviceName: String get() = "kotlinSpecFixtureService"
    override val consoleServiceType: Class<ProcessConsoleService> get() = ProcessConsoleService::class.java

    override fun configureConsoleService(rootProject: Project, parameters: ProcessConsoleService.Params) {
        parameters.spec.set(KOTLIN_PROCESSES)
        parameters.jStat.set(ProcessReport.jStat(rootProject.providers, KOTLIN_PROCESSES))
        parameters.jInfo.set(ProcessReport.jInfo(rootProject.providers, KOTLIN_PROCESSES))
    }

    override fun consoleAlongsideDevelocity(rootProject: Project): Provider<Boolean> = rootProject.providers.provider { false }

    override fun buildScanReporter(rootProject: Project, access: DevelocityAccess): BuildScanReporter =
        EchoingReporter(
            ProcessReport.buildScanReporter(
                KOTLIN_PROCESSES,
                ProcessReport.jStat(rootProject.providers, KOTLIN_PROCESSES),
                ProcessReport.jInfo(rootProject.providers, KOTLIN_PROCESSES),
                BooleanOptIn.gradleProperty(providers, "kotlinSpec.gbos.enabled").orElse(false),
            ),
            BooleanOptIn.gradleProperty(providers, "fixture.echoReporter").getOrElse(false),
        )
}

class EchoingReporter(private val delegate: BuildScanReporter, private val echo: Boolean) : BuildScanReporter {
    override fun report(sink: BuildScanSink) {
        delegate.report(if (echo) EchoSink(sink) else sink)
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
