# jvm-process-report

`io.github.cdsap:jvm-process-report` reports the Gradle or Kotlin daemons a build left running. InfoGradleProcess and
InfoKotlinProcess use it, each with its own spec:

- `ProcessReportSpec` names the `jps` process to look for, its `TypeProcess`, the console title, the custom-value key
  prefix and an optional `GbosProducerSpec`. Title and key prefix always come from the spec, never from
  `Process.typeProcess`.
- `ProcessReport.jStat` / `ProcessReport.jInfo` are lazy `jstat`/`jinfo` value sources. Their commands are the ones
  `commandline-value-source` 0.1.0 runs, byte for byte; an empty or failed command yields "" and never fails the build.
- `ProcessReport.collect`, `consoleTable` and `writeBuildScanValues` turn that output into processes, the console
  table and the Build Scan values (`<keyPrefix>-<pid>-{max,usage,capacity,uptime,gcTime,gcType}`, or the GBOS
  projection through `build-observability-core`'s `GbosDevelocity.publish` when the plugin's opt-in is on).
- `ProcessConsoleService` and `ProcessReport.buildScanReporter` plug those into `plugin-support`'s `PluginEntry`.
  Configure the service with `ProcessReport.configureConsoleService`: Gradle reads service parameters whenever it
  creates the service, so it gates the `jps`/`jstat`/`jinfo` providers on the console policy. When Develocity replaces
  the console table, the tools then run once, for the Build Scan values; they run twice only when both outputs print.

It is released in lockstep with `plugin-support` under the same version, so the plugin's
`PluginSupportVersion.requireCompatible` call covers both libraries. Call it first in `apply`:

```kotlin
private val KOTLIN_PROCESSES = ProcessReportSpec(
    processName = "KotlinCompileDaemon",
    typeProcess = TypeProcess.Kotlin,
    consoleTitle = "Kotlin processes",
    keyPrefix = "Kotlin-Process",
    gbos = GbosProducerSpec(
        schemaVersion = "1.0.0",
        producerName = "info-kotlin-process",
        producerVersion = "0.0.4",
        processRole = "kotlin-daemon",
    ),
)

abstract class InfoKotlinProcessPlugin @Inject constructor(
    private val registry: BuildEventsListenerRegistry,
) : Plugin<Any> {
    override fun apply(target: Any) {
        PluginSupportVersion.requireCompatible("io.github.cdsap.kotlinprocess", "0.1.0", "1.0.0")
        PluginEntry.apply(target, registry, KotlinProcessSpec())
    }
}

class KotlinProcessSpec : ReportingPluginSpec<ProcessConsoleService.Params>() {
    override val pluginId = "io.github.cdsap.kotlinprocess"
    override val minLibraryVersion = "0.1.0"
    override val maxLibraryVersionExclusive = "1.0.0"
    override val serviceName = "kotlinProcessService"
    override val consoleServiceType = ProcessConsoleService::class.java

    override fun configureConsoleService(rootProject: Project, parameters: ProcessConsoleService.Params) {
        ProcessReport.configureConsoleService(parameters, rootProject.providers, KOTLIN_PROCESSES)
    }

    // InfoKotlinProcess prints no console table when Develocity reports the values.
    override fun consoleAlongsideDevelocity(rootProject: Project) = rootProject.providers.provider { false }

    override fun buildScanReporter(rootProject: Project, access: DevelocityAccess) =
        ProcessReport.buildScanReporter(
            KOTLIN_PROCESSES,
            ProcessReport.jStat(rootProject.providers, KOTLIN_PROCESSES),
            ProcessReport.jInfo(rootProject.providers, KOTLIN_PROCESSES),
            // The plugin keeps its own opt-in: infoKotlinProcess.gbos.develocity.enabled and its DSL.
            gbosEnabled(rootProject),
        )
}
```

With GBOS enabled, the GBOS values replace the legacy ones. The header is written as `gbos.schema`, then
`gbos.v1.producer.<slug>.version`, then `.name`, then one `.observation` per daemon. InfoKotlinProcess 0.3.0 wrote
`.name` before `.version`, and wrote whole numbers in observations as `15.0` where the library writes `15`; parsed
observations are otherwise identical.

The library depends on `build-observability-core` 0.0.7 from Maven Central. To build against a local checkout of
core instead, pass `-PgbosCoreBuild=../build-observability-schema`; see [docs/releasing.md](../docs/releasing.md).

Public ABI changes must be recorded with `./gradlew :jvm-process-report:updateKotlinAbi`.
