# InfoProcess

`InfoProcess` is a settings plugin that unifies Gradle daemon, Kotlin daemon,
test worker, and GC observations behind one configuration point.

```kotlin
plugins {
    id("io.github.cdsap.infoprocess") version "1.0.0"
}
```

The initial implementation is intentionally resilient: missing process tools
and optional GC logs become diagnostics rather than build failures. The plugin
does not enable GC logging retroactively; configure JVM logging in
`gradle.properties` before the daemon starts.

## Configuration

```kotlin
infoProcess {
    gradleProcess.enabled.set(false)
    gcReport.logs.from(layout.settingsDirectory.file("gradle_gc.log"))
    gcReport.histogram.enabled.set(true)
    reporting.outputDirectory.set(layout.buildDirectory.dir("reports/info-process"))
}
```

Every boolean option also accepts the corresponding `infoProcess.*` Gradle
property. Explicit DSL values override properties, and invalid boolean values
fail during settings configuration.

