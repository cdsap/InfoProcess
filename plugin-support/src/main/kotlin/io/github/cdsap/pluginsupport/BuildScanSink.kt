package io.github.cdsap.pluginsupport

import java.io.Serializable

/**
 * Build Scan custom values and tags, without Develocity types.
 *
 * Plugins write through this interface and fake it in unit tests. It adapts directly to the
 * `build-observability-core` publisher sink:
 *
 * ```
 * GbosDevelocity.publish(GbosCustomValueSink { key, value -> sink.value(key, value) }, ...)
 * ```
 */
public interface BuildScanSink {
    /** Adds the custom value [key] = [value] to the Build Scan. */
    public fun value(key: String, value: String)

    /** Adds the tag [name] to the Build Scan. */
    public fun tag(name: String)
}

/**
 * Writes a plugin's Build Scan data at the end of the build, from Develocity's `buildScan.buildFinished`.
 *
 * Instances are stored in the configuration cache and run again when it is reused, so they may only
 * capture configuration-cache-serializable state: providers and plain values, never `Project`,
 * `Settings` or `Gradle`. Read providers inside [report], not when creating the reporter.
 */
public fun interface BuildScanReporter : Serializable {
    public fun report(sink: BuildScanSink)
}
