package io.github.cdsap.pluginsupport

import com.gradle.develocity.agent.gradle.DevelocityConfiguration
import com.gradle.develocity.agent.gradle.scan.BuildScanConfiguration
import io.github.cdsap.pluginsupport.fake.FakeProxies
import io.github.cdsap.pluginsupport.fake.ScanSink
import io.github.cdsap.pluginsupport.support.IsolatingClassLoader
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevelocityBridgeTest {
    @TempDir
    lateinit var tempDir: File

    private val lines = mutableListOf<String>()
    private val finishedActions = mutableListOf<Any>()

    @Test
    fun `develocity whose API type the library links against is DEVELOCITY`() {
        val root = rootProject()
        root.extensions.add("develocity", FakeProxies.develocity(finishedActions, sink()))

        val develocity = assertNotNull(DevelocityBridge.detect(root, legacyGradleEnterprise = false))

        assertEquals(DevelocityAccess.DEVELOCITY, develocity.access)
        assertFalse(develocity.access.keepsConsole)
    }

    @Test
    fun `develocity from a classloader the library cannot see is DEVELOCITY_ISOLATED and still reports`() {
        val root = rootProject()
        val isolated = IsolatingClassLoader(listOf(develocityJar()), javaClass.classLoader, listOf("com.gradle."))
        val configurationType = isolated.loadClass(DevelocityConfiguration::class.java.name)
        val buildScanType = isolated.loadClass(BuildScanConfiguration::class.java.name)
        check(configurationType !== DevelocityConfiguration::class.java)
        root.extensions.add("develocity", FakeProxies.develocity(configurationType, buildScanType, finishedActions, sink()))

        val develocity = assertNotNull(DevelocityBridge.detect(root, legacyGradleEnterprise = false))

        assertEquals(DevelocityAccess.DEVELOCITY_ISOLATED, develocity.access)
        assertTrue(develocity.access.keepsConsole)
        assertEquals(EXPECTED_LINES, report(develocity))
    }

    @Test
    fun `legacy gradle enterprise is only detected with the opt-in`() {
        val root = rootProject()
        root.extensions.add("gradleEnterprise", FakeProxies.gradleEnterprise(finishedActions, sink()))

        assertNull(DevelocityBridge.detect(root, legacyGradleEnterprise = false))
        val develocity = assertNotNull(DevelocityBridge.detect(root, legacyGradleEnterprise = true))
        assertEquals(DevelocityAccess.GRADLE_ENTERPRISE, develocity.access)
        assertTrue(develocity.access.keepsConsole)
        assertEquals(EXPECTED_LINES, report(develocity))
    }

    @Test
    fun `develocity is preferred over gradle enterprise`() {
        val root = rootProject()
        root.extensions.add("gradleEnterprise", FakeProxies.gradleEnterprise(mutableListOf(), sink()))
        root.extensions.add("develocity", FakeProxies.develocity(finishedActions, sink()))

        assertEquals(DevelocityAccess.DEVELOCITY, DevelocityBridge.detect(root, legacyGradleEnterprise = true)?.access)
    }

    @Test
    fun `an unrelated extension named develocity is ignored`() {
        val root = rootProject()
        root.extensions.add("develocity", "not Develocity")

        assertNull(DevelocityBridge.detect(root, legacyGradleEnterprise = true))
    }

    @Test
    fun `reporter values reach the build scan only when buildFinished runs`() {
        val root = rootProject()
        root.extensions.add("develocity", FakeProxies.develocity(finishedActions, sink()))

        assertEquals(EXPECTED_LINES, report(assertNotNull(DevelocityBridge.detect(root, legacyGradleEnterprise = false))))
    }

    @Test
    fun `dispatcher rejects targets other than Settings and Project`() {
        val registry = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BuildEventsListenerRegistry::class.java)) { _, _, _ -> null }
        val failure =
            assertFailsWith<GradleException> {
                PluginEntry.apply("a task", registry as BuildEventsListenerRegistry, io.github.cdsap.pluginsupport.fixture.FixtureSpec(rootProject().providers))
            }
        assertEquals("io.github.cdsap.pluginsupport.fixture can only be applied to Settings or Project, not java.lang.String", failure.message)
    }

    @Test
    fun `access kinds compare by name`() {
        assertEquals("DEVELOCITY_ISOLATED", DevelocityAccess.DEVELOCITY_ISOLATED.toString())
        assertFalse(DevelocityAccess.DEVELOCITY == DevelocityAccess.GRADLE_ENTERPRISE)
    }

    private fun report(develocity: DetectedDevelocity): List<String> {
        develocity.onBuildFinished { sink ->
            sink.value("example.key", "example value")
            sink.tag("example")
        }
        assertEquals(emptyList(), lines, "values are only written from buildFinished")
        assertEquals(1, finishedActions.size)
        @Suppress("UNCHECKED_CAST")
        (finishedActions.single() as Action<Any>).execute(FakeProxies.buildResult())
        return lines
    }

    private fun sink() = ScanSink { lines += it }

    private fun rootProject(): Project = ProjectBuilder.builder().withProjectDir(File(tempDir, "root").apply { mkdirs() }).build()

    private fun develocityJar() = DevelocityConfiguration::class.java.protectionDomain.codeSource.location

    private companion object {
        val EXPECTED_LINES = listOf("SCAN-VALUE example.key=example value", "SCAN-TAG example")
    }
}
