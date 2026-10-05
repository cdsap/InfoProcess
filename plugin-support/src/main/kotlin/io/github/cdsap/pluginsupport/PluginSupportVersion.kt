package io.github.cdsap.pluginsupport

import org.gradle.api.GradleException
import java.io.InputStream
import java.net.URL
import java.util.Properties

/**
 * Version guard for the shared cdsap plugin libraries (`plugin-support` and, released in lockstep,
 * `jvm-process-report`).
 *
 * Gradle loads build-script plugin classes parent-first through the settings classloader, so a plugin
 * compiled against a newer library can run against an older copy brought by another cdsap plugin. Call
 * [requireCompatible] as the very first statement of `Plugin.apply`, before any other library call, so
 * the build fails with a fix-it message instead of a `NoSuchMethodError`. [PluginEntry] does this for
 * plugins that use the dispatcher.
 *
 * ```
 * PluginSupportVersion.requireCompatible("io.github.cdsap.gcreport", minInclusive = "1.1.0", maxExclusive = "2.0.0")
 * ```
 *
 * The name and signatures of this object are frozen: every later plugin release must be able to call it
 * against any older library. It only uses the JDK and the Gradle API, never another library class.
 *
 * Versions are compared on their numeric `MAJOR.MINOR.PATCH` part (missing parts count as 0). A
 * qualifier such as `-SNAPSHOT` or `-rc1` is ignored, so `1.2.0-SNAPSHOT` satisfies a minimum of
 * `1.2.0` (local `--include-build` development) and `2.0.0-SNAPSHOT` is outside `[1.0.0, 2.0.0)`.
 */
public object PluginSupportVersion {
    private const val RESOURCE = "plugin-support-version.properties"
    private const val UNKNOWN = "unknown"

    /**
     * Version of the `plugin-support` jar this class was loaded from, read at runtime from a resource
     * generated at build time. It is not a compile-time constant, so callers never inline their own
     * compile-time version.
     */
    public val loaded: String = readLoadedVersion()

    /**
     * Fails the build with a [GradleException] when [loaded] is outside `[minInclusive, maxExclusive)`.
     *
     * @param pluginId id of the calling plugin, used in the error message.
     * @param minInclusive the library version the plugin was compiled against.
     * @param maxExclusive the next major version of the library.
     */
    public fun requireCompatible(pluginId: String, minInclusive: String, maxExclusive: String) {
        val min = parse(minInclusive)
        val max = parse(maxExclusive)
        require(min != null && max != null && compare(min, max) < 0) {
            "Plugin '$pluginId' declares an invalid plugin-support range [$minInclusive, $maxExclusive)."
        }
        val current = parse(loaded)
        if (current == null || compare(current, min) < 0 || compare(current, max) >= 0) {
            throw GradleException(
                "Plugin '$pluginId' supports io.github.cdsap:plugin-support [$minInclusive, $maxExclusive), " +
                    "but version $loaded is loaded. A different plugin-support version is on the build classpath, " +
                    "usually brought by another cdsap plugin applied in settings or a parent project. " +
                    "To fix this, align the cdsap plugin versions or apply them all in settings.",
            )
        }
    }

    private fun readLoadedVersion(): String =
        try {
            val properties = Properties()
            openOwnResource()?.use { properties.load(it) }
            properties.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN
        } catch (_: Exception) {
            UNKNOWN
        }

    // Classloader lookups are parent-first, so they can return another copy's resource. The jar this
    // class was defined from identifies the loaded copy; exploded class directories (tests) fall back to
    // the regular lookup.
    private fun openOwnResource(): InputStream? {
        val type = PluginSupportVersion::class.java
        val location = type.protectionDomain?.codeSource?.location
        val resource = "io/github/cdsap/pluginsupport/$RESOURCE"
        if (location != null && !location.path.endsWith("/")) {
            val connection = URL("jar:$location!/$resource").openConnection()
            connection.useCaches = false
            return connection.getInputStream()
        }
        if (location != null) {
            try {
                return URL(location, resource).openStream()
            } catch (_: Exception) {
                // Resources live in another directory; use the classloader lookup below.
            }
        }
        return type.getResourceAsStream(RESOURCE)
    }

    private fun parse(version: String): IntArray? {
        val parts = version.trim().substringBefore('-').split('.')
        if (parts.size > 3) return null
        val numbers = IntArray(3)
        for (index in parts.indices) {
            val number = parts[index].toIntOrNull() ?: return null
            if (number < 0) return null
            numbers[index] = number
        }
        return numbers
    }

    private fun compare(left: IntArray, right: IntArray): Int {
        for (index in 0 until 3) {
            if (left[index] != right[index]) return left[index].compareTo(right[index])
        }
        return 0
    }
}
