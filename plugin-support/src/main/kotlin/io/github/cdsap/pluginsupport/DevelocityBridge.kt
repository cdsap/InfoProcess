package io.github.cdsap.pluginsupport

import com.gradle.develocity.agent.gradle.adapters.BuildResultAdapter
import com.gradle.develocity.agent.gradle.adapters.BuildScanAdapter
import com.gradle.develocity.agent.gradle.adapters.DevelocityAdapter
import com.gradle.develocity.agent.gradle.adapters.develocity.DevelocityConfigurationAdapter
import com.gradle.develocity.agent.gradle.adapters.enterprise.GradleEnterpriseExtensionAdapter
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.ExtensionAware

/**
 * How the Develocity bridge found Develocity. Compare with the constants; new kinds may be added.
 */
public class DevelocityAccess private constructor(
    private val name: String,
    /**
     * True when console output must stay on whatever the plugin's own console rule says: these paths were
     * not reachable by the per-plugin Develocity detection, so their users only ever saw the console output.
     */
    public val keepsConsole: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is DevelocityAccess && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    public companion object {
        /** The `develocity` extension, implementing the Develocity API type the plugin's classloader links against. */
        @JvmField
        public val DEVELOCITY: DevelocityAccess = DevelocityAccess("DEVELOCITY", keepsConsole = false)

        /**
         * The `develocity` extension of a Develocity plugin loaded by a classloader the plugin cannot see, typically
         * injected by an init script. A typed `DevelocityConfiguration` cast fails here; the bridge works reflectively.
         */
        @JvmField
        public val DEVELOCITY_ISOLATED: DevelocityAccess = DevelocityAccess("DEVELOCITY_ISOLATED", keepsConsole = true)

        /** The legacy `gradleEnterprise` extension of `com.gradle.enterprise`; only looked up when the plugin opts in. */
        @JvmField
        public val GRADLE_ENTERPRISE: DevelocityAccess = DevelocityAccess("GRADLE_ENTERPRISE", keepsConsole = true)
    }
}

/** Develocity found by [DevelocityBridge], without exposing Develocity types. */
public abstract class DetectedDevelocity internal constructor() {
    public abstract val access: DevelocityAccess

    /**
     * Runs [reporter] from Develocity's `buildScan.buildFinished`, also on configuration-cache reuse.
     * Call once per build; every call registers another callback.
     */
    public abstract fun onBuildFinished(reporter: BuildScanReporter)
}

/** Receives the result of [DevelocityBridge.detect] for a settings plugin; [develocity] is null when absent. */
public fun interface DevelocityDetectedAction {
    public fun execute(rootProject: Project, develocity: DetectedDevelocity?)
}

/**
 * Finds Develocity by extension name and drives it through `com.gradle:develocity-gradle-plugin-adapters`, so
 * plugins need no Develocity types and Develocity injected by an init script works too.
 */
public object DevelocityBridge {
    private const val DEVELOCITY_PLUGIN_ID = "com.gradle.develocity"
    private const val GRADLE_ENTERPRISE_PLUGIN_ID = "com.gradle.enterprise"
    private const val DEVELOCITY_EXTENSION = "develocity"
    private const val GRADLE_ENTERPRISE_EXTENSION = "gradleEnterprise"
    private const val DEVELOCITY_CONFIGURATION = "com.gradle.develocity.agent.gradle.DevelocityConfiguration"

    private val logger = Logging.getLogger(DevelocityBridge::class.java)

    /**
     * For settings plugins. Call from `apply`: it registers `pluginManager.withPlugin` hooks immediately, so
     * Develocity applied before or after the plugin (or auto-applied by `--scan`) is seen. [action] runs once from
     * `gradle.rootProject {}`, after the settings script, with the extension found by the hooks or, failing that,
     * by name on the settings and then the root project (init-script injection). The legacy `com.gradle.enterprise`
     * plugin and its `gradleEnterprise` extension are only considered when [legacyGradleEnterprise] is true.
     */
    public fun detect(settings: Settings, legacyGradleEnterprise: Boolean, action: DevelocityDetectedAction) {
        val hooked = HookedExtensions()
        settings.pluginManager.withPlugin(DEVELOCITY_PLUGIN_ID) {
            hooked.develocity = settings.extensions.findByName(DEVELOCITY_EXTENSION)
        }
        if (legacyGradleEnterprise) {
            settings.pluginManager.withPlugin(GRADLE_ENTERPRISE_PLUGIN_ID) {
                hooked.gradleEnterprise = settings.extensions.findByName(GRADLE_ENTERPRISE_EXTENSION)
            }
        }
        settings.gradle.rootProject(
            Action { rootProject ->
                val develocity = hooked.develocity ?: findExtension(DEVELOCITY_EXTENSION, settings, rootProject)
                val gradleEnterprise =
                    if (legacyGradleEnterprise) {
                        hooked.gradleEnterprise ?: findExtension(GRADLE_ENTERPRISE_EXTENSION, settings, rootProject)
                    } else {
                        null
                    }
                action.execute(rootProject, adapt(develocity, gradleEnterprise))
            },
        )
    }

    /**
     * For plugins applied to a project: looks the extension up by name on [rootProject], where Develocity (applied
     * in settings) and Gradle Enterprise 3.x also install it. Returns null when absent.
     */
    public fun detect(rootProject: Project, legacyGradleEnterprise: Boolean): DetectedDevelocity? {
        val develocity = rootProject.extensions.findByName(DEVELOCITY_EXTENSION)
        val gradleEnterprise =
            if (legacyGradleEnterprise) rootProject.extensions.findByName(GRADLE_ENTERPRISE_EXTENSION) else null
        return adapt(develocity, gradleEnterprise)
    }

    internal fun adapt(develocity: Any?, gradleEnterprise: Any?): DetectedDevelocity? {
        if (develocity != null && adapterFor(develocity, legacy = false) != null) {
            return AdaptedDevelocity(classify(develocity), develocity, legacy = false)
        }
        if (gradleEnterprise != null && adapterFor(gradleEnterprise, legacy = true) != null) {
            return AdaptedDevelocity(DevelocityAccess.GRADLE_ENTERPRISE, gradleEnterprise, legacy = true)
        }
        return null
    }

    internal fun adapterFor(extension: Any, legacy: Boolean): DevelocityAdapter? =
        try {
            if (legacy) GradleEnterpriseExtensionAdapter(extension) else DevelocityConfigurationAdapter(extension)
        } catch (e: RuntimeException) {
            logger.warn("Ignoring extension of type {}: it is not a supported Develocity extension ({})", extension.javaClass.name, e.message)
            null
        }

    private fun classify(develocity: Any): DevelocityAccess {
        val linkedType =
            try {
                Class.forName(DEVELOCITY_CONFIGURATION, false, DevelocityBridge::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: LinkageError) {
                null
            }
        return if (linkedType != null && linkedType.isInstance(develocity)) {
            DevelocityAccess.DEVELOCITY
        } else {
            DevelocityAccess.DEVELOCITY_ISOLATED
        }
    }

    private fun findExtension(name: String, vararg hosts: ExtensionAware): Any? {
        for (host in hosts) {
            host.extensions.findByName(name)?.let { return it }
        }
        return null
    }

    private class HookedExtensions {
        var develocity: Any? = null
        var gradleEnterprise: Any? = null
    }
}

private class AdaptedDevelocity(
    override val access: DevelocityAccess,
    private val extension: Any,
    private val legacy: Boolean,
) : DetectedDevelocity() {
    override fun onBuildFinished(reporter: BuildScanReporter) {
        val adapter = checkNotNull(DevelocityBridge.adapterFor(extension, legacy))
        adapter.buildScan.buildFinished(BuildFinishedReport(extension, legacy, reporter))
    }
}

// Stored by Develocity (and the configuration cache) until the build finishes: it captures the raw extension and
// the plugin's reporter only, and adapts the extension when it runs.
internal class BuildFinishedReport(
    private val extension: Any,
    private val legacy: Boolean,
    private val reporter: BuildScanReporter,
) : Action<BuildResultAdapter> {
    override fun execute(result: BuildResultAdapter) {
        val adapter = checkNotNull(DevelocityBridge.adapterFor(extension, legacy))
        reporter.report(AdapterBuildScanSink(adapter.buildScan))
    }
}

internal class AdapterBuildScanSink(private val buildScan: BuildScanAdapter) : BuildScanSink {
    override fun value(key: String, value: String) {
        buildScan.value(key, value)
    }

    override fun tag(name: String) {
        buildScan.tag(name)
    }
}
