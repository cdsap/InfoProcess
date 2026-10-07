# plugin-support

`io.github.cdsap:plugin-support` holds the code shared by the cdsap Gradle plugins:

- `PluginSupportVersion.requireCompatible(pluginId, min, maxExclusive)`, the version guard. Its signature is frozen.
- `DevelocityBridge`, which finds Develocity (or, if you opt in, the legacy Gradle Enterprise plugin) through
  `develocity-gradle-plugin-adapters`. It reports how it was found as a `DevelocityAccess`.
- `BuildScanSink` and `BuildScanReporter`, which write values and tags without a dependency on Develocity types.
- `PluginEntry` with `ReportingPluginSpec`, which dispatches Settings and Project applications. Configuration
  happens once per build.
- `ConsoleReportService`, the base build service for the console report.
- `BooleanOptIn`, for Gradle-property defaults that the DSL can override.

A plugin built on the dispatcher looks like this:

```kotlin
abstract class MyPlugin @Inject constructor(
    private val registry: BuildEventsListenerRegistry,
) : Plugin<Any> {
    override fun apply(target: Any) {
        PluginSupportVersion.requireCompatible("io.github.cdsap.my-plugin", "1.0.0", "2.0.0")
        PluginEntry.apply(target, registry, MySpec())
    }
}
```

Every plugin calls `PluginSupportVersion.requireCompatible` itself as the first line of `apply`, before it constructs
its spec. `ReportingPluginSpec` is a library class, so the check must not depend on it linking against whatever
library version was loaded. `PluginEntry.apply` repeats the check.

`MySpec` extends `ReportingPluginSpec`. It provides the plugin id, the supported plugin-support range (from the
version compiled against, up to the next major), the service name, the console service, and the Build Scan reporter.
For the full example and rules, see the KDoc on `PluginEntry` and `PluginSupportVersion`.

Pass a new spec to every `PluginEntry.apply` call. All hooks of one application run on that instance, so a spec can
keep state between hooks, such as the extension it created in `applyToSettings`. `ReportingPluginSpec` documents the
hook order. The optional hooks all have defaults:

- `applyToSettings(settings, develocityPresent)` gets a provider that becomes true once Develocity is found, after the
  settings script. Use it for lazy conventions.
- `configureConsoleService(rootProject, parameters, develocity)` also gets how Develocity was found, or null.
- `consoleAlongsideDevelocity(rootProject, application)` also gets where the configuring application was applied
  (`PluginApplication.SETTINGS`, `ROOT_PROJECT` or `SUBPROJECT`). Use it when a subproject application used to miss
  Develocity, so its users must keep the console report.
- `subscribeToTaskCompletion = false` leaves the console service unsubscribed, for a service that only tasks declaring
  it with `usesService` start.
- `onConfigured(rootProject, service, develocity)` runs once per build after registration, for example to wire tasks
  to the service.

Version comparison ignores qualifiers: `1.2.0-SNAPSHOT` is treated as `1.2.0`.

Public ABI changes must be recorded with `./gradlew :plugin-support:updateKotlinAbi`. For releases, see
[docs/releasing.md](../docs/releasing.md).
