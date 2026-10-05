package io.github.cdsap.plugintest

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

/** The two builds of a configuration-cache double run: [store] wrote the cache entry, [reuse] loaded it. */
public class ConfigurationCacheRuns internal constructor(
    public val store: BuildResult,
    public val reuse: BuildResult,
)

/**
 * Configuration-cache double run: the same build twice with `--configuration-cache`, checking that the first run
 * stored an entry and the second reused it. Failures throw [AssertionError] with the build output, so the helper
 * works with any test framework.
 *
 * ```
 * val runs = ConfigurationCache.storeAndReuse(runner, "help")
 * assertEquals(scanValues(runs.store.output), scanValues(runs.reuse.output))
 * ```
 */
public object ConfigurationCache {
    /** Printed by Gradle 8.2 and later when a build stores a configuration cache entry. */
    public const val STORED: String = "Configuration cache entry stored."

    /** Printed by Gradle 8.2 and later when a build reuses a configuration cache entry. */
    public const val REUSED: String = "Reusing configuration cache."

    /**
     * Runs [runner] twice with [arguments] plus `--configuration-cache`. The runner's arguments are replaced. Both
     * builds must succeed; problems fail the build unless the arguments say otherwise.
     */
    public fun storeAndReuse(runner: GradleRunner, vararg arguments: String): ConfigurationCacheRuns =
        runTwice(runner, arguments.toList() + "--configuration-cache")

    internal fun runTwice(runner: GradleRunner, arguments: List<String>): ConfigurationCacheRuns {
        val store = runner.withArguments(arguments).build()
        if (!store.output.contains(STORED) || store.output.contains(REUSED)) {
            throw AssertionError("Expected the first build to store a configuration cache entry:\n${store.output}")
        }
        val reuse = runner.withArguments(arguments).build()
        if (!reuse.output.contains(REUSED)) {
            throw AssertionError("Expected the second build to reuse the configuration cache entry:\n${reuse.output}")
        }
        return ConfigurationCacheRuns(store, reuse)
    }
}

/**
 * Isolated Projects double run, like [ConfigurationCache.storeAndReuse] with Isolated Projects enabled (which implies
 * the configuration cache). Isolated Projects violations are configuration cache problems, which fail the build.
 */
public object IsolatedProjects {
    /** The Gradle property that enables Isolated Projects on Gradle 8.2 through 9.x. */
    public const val PROPERTY: String = "org.gradle.unsafe.isolated-projects"

    /** Runs [runner] twice with [arguments] plus `-Dorg.gradle.unsafe.isolated-projects=true`. */
    public fun storeAndReuse(runner: GradleRunner, vararg arguments: String): ConfigurationCacheRuns =
        ConfigurationCache.runTwice(runner, arguments.toList() + "-D$PROPERTY=true")
}
