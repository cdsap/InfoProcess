@file:Suppress("DEPRECATION")

package io.github.cdsap.plugintest

import com.gradle.develocity.agent.gradle.DevelocityConfiguration
import com.gradle.develocity.agent.gradle.scan.BuildResult
import com.gradle.develocity.agent.gradle.scan.BuildScanConfiguration
import com.gradle.enterprise.gradleplugin.GradleEnterpriseExtension
import com.gradle.scan.plugin.BuildScanExtension
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.util.Collections
import javax.inject.Inject

/**
 * Offline stand-in for the Develocity settings plugin, applied in TestKit builds under the real id
 * `com.gradle.develocity` (see [FakeDevelocity] for the ways to apply it).
 *
 * Like real Develocity 4.x it registers the `develocity` extension on the settings and adds the same instance to the
 * root project from a `gradle.rootProject {}` hook. The extension is a [Proxy] of the real
 * `com.gradle.develocity.agent.gradle.DevelocityConfiguration` interface, so `withPlugin("com.gradle.develocity")`
 * hooks, typed lookups and `develocity-gradle-plugin-adapters` all accept it. Nothing is published:
 * - `buildScan.value(name, value)` prints `SCAN-VALUE <name>=<value>`,
 * - `buildScan.tag(tag)` prints `SCAN-TAG <tag>`,
 * - `buildScan.buildFinished(action)` actions run at the end of the build, also when the configuration cache is reused.
 *
 * Other members return defaults (`Property` instances, nested proxies, `false`), so settings scripts can configure
 * `develocity { server = "..."; buildScan { publishing.onlyIf { false } } }` as they would the real extension.
 *
 * The end-of-build replay finalizes every task from `gradle.allprojects {}`, so the fake does not support Isolated
 * Projects. The `com.gradle.develocity.agent.gradle` API must be loadable from this class's classloader.
 */
public abstract class FakeDevelocityPlugin @Inject constructor(private val objects: ObjectFactory) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        println(FakeDevelocity.APPLIED_MARKER)
        val finishedActions = mutableListOf<Any>()
        val buildScan = FakeProxies.create(BuildScanConfiguration::class.java, objects, BuildScanBehaviour(finishedActions))
        val develocity = FakeProxies.create(DevelocityConfiguration::class.java, objects, ConfigurationBehaviour(buildScan))
        settings.extensions.add(DevelocityConfiguration::class.java, "develocity", develocity)
        settings.gradle.rootProject(
            Action<Project> { it.extensions.add(DevelocityConfiguration::class.java, "develocity", develocity) },
        )
        FakeBuildFinishedWiring.install(settings, finishedActions)
    }
}

/**
 * Offline stand-in for the legacy Gradle Enterprise settings plugin, applied under the id `com.gradle.enterprise`.
 *
 * Like Gradle Enterprise 3.x it registers the `gradleEnterprise` extension (a [Proxy] of the real
 * `com.gradle.enterprise.gradleplugin.GradleEnterpriseExtension`) on the settings and on the root project, and prints
 * and replays Build Scan calls like [FakeDevelocityPlugin]. It never fires `withPlugin("com.gradle.develocity")`.
 */
public abstract class FakeGradleEnterprisePlugin @Inject constructor(private val objects: ObjectFactory) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        println(FakeDevelocity.LEGACY_APPLIED_MARKER)
        val finishedActions = mutableListOf<Any>()
        val buildScan = FakeProxies.create(BuildScanExtension::class.java, objects, BuildScanBehaviour(finishedActions))
        val gradleEnterprise = FakeProxies.create(GradleEnterpriseExtension::class.java, objects, ConfigurationBehaviour(buildScan))
        settings.extensions.add(GradleEnterpriseExtension::class.java, "gradleEnterprise", gradleEnterprise)
        settings.gradle.rootProject(
            Action<Project> { it.extensions.add(GradleEnterpriseExtension::class.java, "gradleEnterprise", gradleEnterprise) },
        )
        FakeBuildFinishedWiring.install(settings, finishedActions)
    }
}

internal object FakeBuildFinishedWiring {
    // Build services are closed in registration-name order; sorting last prints the scan values after the plugins'
    // console reports.
    private const val SERVICE_NAME = "zzzFakeDevelocityBuildFinished"
    private const val HANDOFF_TASK_NAME = "fakeDevelocityBuildFinished"

    fun install(settings: Settings, finishedActions: List<Any>) {
        val service = settings.gradle.sharedServices.registerIfAbsent(SERVICE_NAME, FakeBuildFinishedService::class.java) {}
        settings.gradle.projectsEvaluated(
            Action<Gradle> { gradle ->
                val root = gradle.rootProject
                val handoff =
                    root.tasks.register(HANDOFF_TASK_NAME, FakeBuildFinishedHandoff::class.java) { task ->
                        task.usesService(service)
                        task.service.set(service)
                        task.finishedActions = finishedActions
                    }
                gradle.allprojects { project ->
                    project.tasks.configureEach { task ->
                        if (!(project == root && task.name == HANDOFF_TASK_NAME)) task.finalizedBy(handoff)
                    }
                }
            },
        )
    }
}

/**
 * Carries the recorded `buildFinished` actions through the configuration cache. Build service parameters are isolated
 * with Java serialization, which arbitrary plugin actions do not support; task fields are serialized by the cache.
 */
internal abstract class FakeBuildFinishedHandoff : DefaultTask() {
    @get:Internal
    abstract val service: Property<FakeBuildFinishedService>

    @get:Internal
    var finishedActions: List<Any> = emptyList()

    @TaskAction
    fun handOff() {
        service.get().finishedActions.addAll(finishedActions)
    }
}

internal abstract class FakeBuildFinishedService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    val finishedActions: MutableList<Any> = Collections.synchronizedList(mutableListOf())

    override fun close() {
        val result = FakeProxies.create(BuildResult::class.java, null, BuildResultBehaviour())
        finishedActions.forEach {
            @Suppress("UNCHECKED_CAST")
            (it as Action<Any>).execute(result)
        }
    }
}

/** Per-type answers; returning [FakeProxies.UNHANDLED] falls back to the defaults. */
internal interface FakeBehaviour {
    fun invoke(proxy: Any, method: Method, args: Array<Any?>): Any?
}

// The recorded actions are transient: plugin actions capture the extension proxies, so the proxies (handlers and
// behaviours) must be configuration-cache serializable, and the actions are only recorded at configuration time.
internal class BuildScanBehaviour(@Transient private var finishedActions: MutableList<Any>?) : FakeBehaviour {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>): Any? {
        when (method.name) {
            "value" -> println("${FakeDevelocity.SCAN_VALUE_PREFIX}${args[0]}=${args[1]}")
            "tag" -> println("${FakeDevelocity.SCAN_TAG_PREFIX}${args[0]}")
            "buildFinished" -> checkNotNull(finishedActions) { "buildFinished registered at execution time" } += args[0]!!
            "background" -> {
                @Suppress("UNCHECKED_CAST")
                (args[0] as Action<Any>).execute(proxy)
            }
            else -> return FakeProxies.UNHANDLED
        }
        return null
    }
}

/** `getBuildScan()` / `buildScan(Action)` of a Develocity or Gradle Enterprise extension. */
internal class ConfigurationBehaviour(private val buildScan: Any) : FakeBehaviour {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>): Any? =
        when (method.name) {
            "getBuildScan" -> buildScan
            "buildScan" -> {
                @Suppress("UNCHECKED_CAST")
                (args[0] as Action<Any>).execute(buildScan)
                null
            }
            else -> FakeProxies.UNHANDLED
        }
}

internal class BuildResultBehaviour : FakeBehaviour {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>): Any? =
        if (method.name == "getFailures") emptyList<Throwable>() else FakeProxies.UNHANDLED
}

internal class DefaultsOnlyBehaviour : FakeBehaviour {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>): Any? = FakeProxies.UNHANDLED
}

internal object FakeProxies {
    val UNHANDLED = Any()

    fun <T> create(type: Class<T>, objects: ObjectFactory?, behaviour: FakeBehaviour): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), DefaultsHandler(type, objects, behaviour)))

    /** Answers whatever [behaviour] leaves [UNHANDLED] with a default; getter results are created once and reused. */
    private class DefaultsHandler(
        private val type: Class<*>,
        @Transient private var objects: ObjectFactory?,
        private val behaviour: FakeBehaviour,
    ) : InvocationHandler {
        @Transient
        private var values: MutableMap<String, Any?>? = null

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val arguments = args ?: emptyArray()
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === arguments[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "Fake${type.simpleName}"
                }
            }
            val answer = behaviour.invoke(proxy, method, arguments)
            if (answer !== UNHANDLED) return answer
            if (arguments.size == 1 && arguments[0] is Action<*>) {
                // For example publishing(Action) configures the object returned by getPublishing().
                val getter =
                    proxy.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.name == "get" + method.name.replaceFirstChar(Char::uppercaseChar)
                    }
                if (getter != null) {
                    @Suppress("UNCHECKED_CAST")
                    (arguments[0] as Action<Any>).execute(getter.invoke(proxy))
                }
                return null
            }
            if (arguments.isNotEmpty()) return null
            return synchronized(this) {
                val cached = values ?: mutableMapOf<String, Any?>().also { values = it }
                cached.getOrPut(method.name) { defaultFor(method) }
            }
        }

        private fun defaultFor(method: Method): Any? {
            val returnType = method.returnType
            val elementTypes =
                (method.genericReturnType as? ParameterizedType)
                    ?.actualTypeArguments
                    ?.map { (it as? Class<*>) ?: Any::class.java }
                    .orEmpty()
            val objects = objects
            return when {
                returnType == Void.TYPE -> null
                returnType == java.lang.Boolean.TYPE -> false
                returnType == Property::class.java -> objects?.property(elementTypes.firstOrNull() ?: Any::class.java)
                returnType == ListProperty::class.java -> objects?.listProperty(elementTypes.firstOrNull() ?: Any::class.java)
                returnType == SetProperty::class.java -> objects?.setProperty(elementTypes.firstOrNull() ?: Any::class.java)
                returnType == MapProperty::class.java ->
                    objects?.mapProperty(elementTypes.getOrNull(0) ?: Any::class.java, elementTypes.getOrNull(1) ?: Any::class.java)
                returnType.isInterface -> create(returnType, objects, DefaultsOnlyBehaviour())
                else -> null
            }
        }
    }
}
