package io.github.cdsap.processreport

import io.github.cdsap.jdk.tools.parser.ConsolidateProcesses
import io.github.cdsap.jdk.tools.parser.model.Process
import io.github.cdsap.pluginsupport.BuildScanReporter
import io.github.cdsap.pluginsupport.BuildScanSink
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * Spec-driven report of the JVM daemons a build left running, shared by InfoGradleProcess and InfoKotlinProcess.
 *
 * A plugin built on `plugin-support`'s `PluginEntry` wires it like this:
 *
 * ```
 * class MySpec : ReportingPluginSpec<ProcessConsoleService.Params>() {
 *     override val consoleServiceType = ProcessConsoleService::class.java
 *
 *     override fun configureConsoleService(rootProject: Project, parameters: ProcessConsoleService.Params) {
 *         ProcessReport.configureConsoleService(parameters, rootProject.providers, SPEC)
 *     }
 *
 *     override fun buildScanReporter(rootProject: Project, access: DevelocityAccess) =
 *         ProcessReport.buildScanReporter(
 *             SPEC,
 *             ProcessReport.jStat(rootProject.providers, SPEC),
 *             ProcessReport.jInfo(rootProject.providers, SPEC),
 *             gbosEnabled,
 *         )
 *     ...
 * }
 * ```
 *
 * `io.github.cdsap:jvm-process-report` is released in lockstep with `plugin-support`, so the plugin's
 * `PluginSupportVersion.requireCompatible` call covers this library too.
 */
public object ProcessReport {
    /**
     * Lazily runs `jps` and `jstat -gc -t` for every daemon named [ProcessReportSpec.processName]. Yields "" when
     * there is none or the command fails; the build never fails because of it.
     */
    public fun jStat(providers: ProviderFactory, spec: ProcessReportSpec): Provider<String> =
        JdkToolCommands.provider(providers, JdkToolCommands.jStat(spec.processName))

    /** Lazily runs `jps` and `jinfo` for every daemon named [ProcessReportSpec.processName]; "" as for [jStat]. */
    public fun jInfo(providers: ProviderFactory, spec: ProcessReportSpec): Provider<String> =
        JdkToolCommands.provider(providers, JdkToolCommands.jInfo(spec.processName))

    /**
     * Sets [ProcessConsoleService.Params] for [spec], for `ReportingPluginSpec.configureConsoleService`. The `jps`,
     * `jstat` and `jinfo` providers are gated on [ProcessConsoleService.Params.consoleEnabled]: Gradle reads service
     * parameters when it creates the service, also when Develocity replaces the console report, and the Build Scan
     * reporter runs the tools itself.
     */
    public fun configureConsoleService(
        parameters: ProcessConsoleService.Params,
        providers: ProviderFactory,
        spec: ProcessReportSpec,
    ) {
        val consoleEnabled = parameters.consoleEnabled.orElse(true)
        val none = providers.provider { "" }
        val jStat = jStat(providers, spec)
        val jInfo = jInfo(providers, spec)
        parameters.spec.set(spec)
        parameters.jStat.set(consoleEnabled.flatMap { if (it) jStat else none })
        parameters.jInfo.set(consoleEnabled.flatMap { if (it) jInfo else none })
    }

    /**
     * Parses [jStat] and [jInfo] output into one [Process] per daemon, in `jstat` order, each typed
     * [ProcessReportSpec.typeProcess]. Empty output gives an empty list.
     */
    public fun collect(spec: ProcessReportSpec, jStat: String, jInfo: String): List<Process> =
        ConsolidateProcesses().consolidate(jStat, jInfo, spec.typeProcess)

    /**
     * The console table titled [ProcessReportSpec.consoleTitle], one row per process. Callers print nothing when
     * [processes] is empty.
     */
    public fun consoleTable(spec: ProcessReportSpec, processes: List<Process>): String =
        ConsoleTable.render(spec.consoleTitle, processes)

    /**
     * Writes [processes] to [sink]. By default these are six values per process, named
     * `<keyPrefix>-<pid>-{max,usage,capacity,uptime,gcTime,gcType}` from [ProcessReportSpec.keyPrefix]. When
     * [gbosEnabled] is true and the spec has a [ProcessReportSpec.gbos] producer, the GBOS projection replaces them.
     * Nothing is written for an empty list.
     */
    public fun writeBuildScanValues(
        spec: ProcessReportSpec,
        processes: List<Process>,
        gbosEnabled: Boolean,
        sink: BuildScanSink,
    ) {
        val gbos = spec.gbos
        if (gbosEnabled && gbos != null) {
            GbosProjection.write(spec, gbos, processes, sink)
        } else {
            LegacyCustomValues.write(spec.keyPrefix, processes, sink)
        }
    }

    /**
     * A reporter for `ReportingPluginSpec.buildScanReporter` that, at the end of the build, collects the daemons from
     * [jStat] and [jInfo] and writes them with [writeBuildScanValues]. [gbosEnabled] is read then, so DSL values set
     * after the plugin was applied count; it is not read when the spec has no GBOS producer.
     */
    public fun buildScanReporter(
        spec: ProcessReportSpec,
        jStat: Provider<String>,
        jInfo: Provider<String>,
        gbosEnabled: Provider<Boolean>,
    ): BuildScanReporter = ProcessBuildScanReporter(spec, jStat, jInfo, gbosEnabled)
}

private class ProcessBuildScanReporter(
    private val spec: ProcessReportSpec,
    private val jStat: Provider<String>,
    private val jInfo: Provider<String>,
    private val gbosEnabled: Provider<Boolean>,
) : BuildScanReporter {
    override fun report(sink: BuildScanSink) {
        val processes = ProcessReport.collect(spec, jStat.get(), jInfo.get())
        ProcessReport.writeBuildScanValues(spec, processes, spec.gbos != null && gbosEnabled.get(), sink)
    }
}
