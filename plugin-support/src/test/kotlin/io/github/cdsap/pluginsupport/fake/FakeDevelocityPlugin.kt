@file:Suppress("DEPRECATION")

package io.github.cdsap.pluginsupport.fake

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
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * Offline stand-in for the Develocity settings plugin, resolved in TestKit builds under the real id
 * `com.gradle.develocity` (`META-INF/gradle-plugins/com.gradle.develocity.properties` in the test resources).
 * Adapted from the GCReport Phase 0 fake; `plugin-test-fixtures` (U8) replaces both copies.
 *
 * Like real Develocity 4.x it registers `develocity` on the settings and adds the same instance to the root
 * project from a `gradle.rootProject {}` hook. The extension is a [Proxy] of the real
 * [DevelocityConfiguration] interface. Build Scan calls print `SCAN-VALUE <name>=<value>` and `SCAN-TAG <tag>`;
 * `buildFinished` actions run at the end of the build, also on configuration-cache reuse.
 */
class FakeDevelocityPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        println(APPLIED_MARKER)
        val finishedActions = mutableListOf<Any>()
        val develocity = FakeProxies.develocity(finishedActions)
        settings.extensions.add(DevelocityConfiguration::class.java, "develocity", develocity)
        settings.gradle.rootProject(Action<Project> { it.extensions.add(DevelocityConfiguration::class.java, "develocity", develocity) })
        FakeBuildFinishedWiring.install(settings, finishedActions)
    }

    companion object {
        const val APPLIED_MARKER = "FAKE-DEVELOCITY applied"
    }
}

/** Offline stand-in for the legacy `com.gradle.enterprise` settings plugin and its `gradleEnterprise` extension. */
class FakeGradleEnterprisePlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        println(APPLIED_MARKER)
        val finishedActions = mutableListOf<Any>()
        val gradleEnterprise = FakeProxies.gradleEnterprise(finishedActions)
        settings.extensions.add(GradleEnterpriseExtension::class.java, "gradleEnterprise", gradleEnterprise)
        settings.gradle.rootProject(
            Action<Project> { it.extensions.add(GradleEnterpriseExtension::class.java, "gradleEnterprise", gradleEnterprise) },
        )
        FakeBuildFinishedWiring.install(settings, finishedActions)
    }

    companion object {
        const val APPLIED_MARKER = "FAKE-GRADLE-ENTERPRISE applied"
    }
}

internal object FakeBuildFinishedWiring {
    // Build services stop in registration-name order; sorting last prints the scan values after the console.
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

/** Carries the recorded buildFinished actions through the configuration cache as a task field. */
abstract class FakeBuildFinishedHandoff : DefaultTask() {
    @get:Internal
    abstract val service: Property<FakeBuildFinishedService>

    @get:Internal
    var finishedActions: List<Any> = emptyList()

    @TaskAction
    fun handOff() {
        service.get().finishedActions.addAll(finishedActions)
    }
}

abstract class FakeBuildFinishedService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    val finishedActions: MutableList<Any> = Collections.synchronizedList(mutableListOf())

    override fun close() {
        val result = FakeProxies.buildResult()
        finishedActions.forEach {
            @Suppress("UNCHECKED_CAST")
            (it as Action<Any>).execute(result)
        }
    }
}

/** Receives the `SCAN-VALUE` / `SCAN-TAG` lines a fake build scan emits. */
fun interface ScanSink {
    fun emit(line: String)
}

class StdoutScanSink : ScanSink {
    override fun emit(line: String) = println(line)
}

object FakeProxies {
    fun develocity(finishedActions: MutableList<Any>, sink: ScanSink = StdoutScanSink()): DevelocityConfiguration =
        develocity(DevelocityConfiguration::class.java, BuildScanConfiguration::class.java, finishedActions, sink)

    /** A Develocity extension implementing [configurationType] / [buildScanType], possibly from another classloader. */
    fun <T> develocity(
        configurationType: Class<T>,
        buildScanType: Class<*>,
        finishedActions: MutableList<Any>,
        sink: ScanSink = StdoutScanSink(),
    ): T {
        val buildScan = create(buildScanType, BuildScanHandler(sink, finishedActions))
        return configurationType.cast(create(configurationType, ConfigurationHandler(buildScan)))
    }

    fun gradleEnterprise(finishedActions: MutableList<Any>, sink: ScanSink = StdoutScanSink()): GradleEnterpriseExtension {
        val buildScan = create(BuildScanExtension::class.java, BuildScanHandler(sink, finishedActions))
        return GradleEnterpriseExtension::class.java.cast(create(GradleEnterpriseExtension::class.java, ConfigurationHandler(buildScan)))
    }

    fun buildResult(): BuildResult = BuildResult::class.java.cast(create(BuildResult::class.java, BuildResultHandler()))

    private fun create(type: Class<*>, handler: InvocationHandler): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
}

// Handlers are plain classes whose only back-reference (the recorded actions) is transient, so proxies captured by
// buildFinished actions are configuration-cache serializable.
private abstract class DefaultsHandler : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        val arguments = args ?: emptyArray()
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "equals" -> proxy === arguments[0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> "Fake${method.declaringClass.simpleName}"
            }
        }
        return handle(proxy, method, arguments)
    }

    abstract fun handle(proxy: Any, method: Method, args: Array<Any?>): Any?

    protected fun default(method: Method): Any? {
        val type = method.returnType
        return when {
            type == java.lang.Boolean.TYPE -> false
            type.isInterface && method.parameterCount == 0 -> Proxy.newProxyInstance(type.classLoader, arrayOf(type), NestedHandler())
            else -> null
        }
    }
}

private class NestedHandler : DefaultsHandler() {
    override fun handle(proxy: Any, method: Method, args: Array<Any?>): Any? = default(method)
}

private class BuildScanHandler(
    private val sink: ScanSink,
    @Transient private var finishedActions: MutableList<Any>?,
) : DefaultsHandler() {
    override fun handle(proxy: Any, method: Method, args: Array<Any?>): Any? {
        when (method.name) {
            "value" -> sink.emit("SCAN-VALUE ${args[0]}=${args[1]}")
            "tag" -> sink.emit("SCAN-TAG ${args[0]}")
            "buildFinished" -> checkNotNull(finishedActions) { "buildFinished registered at execution time" } += args[0]!!
            else -> return default(method)
        }
        return null
    }
}

private class ConfigurationHandler(private val buildScan: Any) : DefaultsHandler() {
    override fun handle(proxy: Any, method: Method, args: Array<Any?>): Any? =
        when (method.name) {
            "getBuildScan" -> buildScan
            "buildScan" -> {
                @Suppress("UNCHECKED_CAST")
                (args[0] as Action<Any>).execute(buildScan)
                null
            }
            else -> default(method)
        }
}

private class BuildResultHandler : DefaultsHandler() {
    override fun handle(proxy: Any, method: Method, args: Array<Any?>): Any? =
        if (method.name == "getFailures") emptyList<Throwable>() else default(method)
}
