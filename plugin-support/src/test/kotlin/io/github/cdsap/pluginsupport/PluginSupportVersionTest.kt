package io.github.cdsap.pluginsupport

import io.github.cdsap.pluginsupport.support.IsolatingClassLoader
import io.github.cdsap.pluginsupport.support.writeJar
import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Most cases load the guard from a jar that contains nothing but `PluginSupportVersion.class` and an optional
 * version resource, in a classloader that cannot fall back to the library on the test classpath. Passing them also
 * proves the guard touches no other library class.
 */
class PluginSupportVersionTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `loaded is the sharedLibsVersion this library was built with`() {
        assertEquals(System.getProperty("pluginSupport.expectedVersion"), PluginSupportVersion.loaded)
    }

    @Test
    fun `loaded version inside the range passes`() {
        val guard = isolatedGuard("1.2.0")
        guard.requireCompatible(PLUGIN_ID, "1.1.0", "2.0.0")
        guard.requireCompatible(PLUGIN_ID, "1.2.0", "2.0.0")
    }

    @Test
    fun `loaded version below the minimum fails with the fix-it message`() {
        val failure = assertFailsWith<GradleException> { isolatedGuard("1.0.0").requireCompatible(PLUGIN_ID, "1.1.0", "2.0.0") }

        assertFixItMessage(failure, loaded = "1.0.0")
    }

    @Test
    fun `loaded version at the exclusive maximum fails with the fix-it message`() {
        val failure = assertFailsWith<GradleException> { isolatedGuard("2.0.0").requireCompatible(PLUGIN_ID, "1.1.0", "2.0.0") }

        assertFixItMessage(failure, loaded = "2.0.0")
    }

    @Test
    fun `qualifiers such as SNAPSHOT are ignored when comparing`() {
        isolatedGuard("1.2.0-SNAPSHOT").requireCompatible(PLUGIN_ID, "1.2.0", "2.0.0")
        isolatedGuard("1.2.0").requireCompatible(PLUGIN_ID, "1.2.0-SNAPSHOT", "2.0.0")
        isolatedGuard("1.2").requireCompatible(PLUGIN_ID, "1.2.0", "2.0")
        assertFailsWith<GradleException> { isolatedGuard("1.1.9-SNAPSHOT").requireCompatible(PLUGIN_ID, "1.2.0", "2.0.0") }
        assertFailsWith<GradleException> { isolatedGuard("2.0.0-SNAPSHOT").requireCompatible(PLUGIN_ID, "1.0.0", "2.0.0") }
    }

    @Test
    fun `versions compare numerically, not as text`() {
        isolatedGuard("1.10.0").requireCompatible(PLUGIN_ID, "1.9.0", "2.0.0")
        assertFailsWith<GradleException> { isolatedGuard("1.9.0").requireCompatible(PLUGIN_ID, "1.10.0", "2.0.0") }
    }

    @Test
    fun `missing version resource reports unknown and fails`() {
        val guard = isolatedGuard(version = null)

        assertEquals("unknown", guard.loaded)
        val failure = assertFailsWith<GradleException> { guard.requireCompatible(PLUGIN_ID, "1.0.0", "2.0.0") }
        assertFixItMessage(failure, loaded = "unknown", range = "[1.0.0, 2.0.0)")
    }

    @Test
    fun `an invalid range is a programming error`() {
        assertFailsWith<IllegalArgumentException> { PluginSupportVersion.requireCompatible(PLUGIN_ID, "two", "3.0.0") }
        assertFailsWith<IllegalArgumentException> { PluginSupportVersion.requireCompatible(PLUGIN_ID, "2.0.0", "2.0.0") }
        assertFailsWith<IllegalArgumentException> { PluginSupportVersion.requireCompatible(PLUGIN_ID, "1.0.0.0", "2.0.0") }
    }

    @Test
    fun `a caller compiled against 1_2_0 sees the 1_0_0 jar a parent classloader loaded first`() {
        // Gradle's settings classloader (1.0.0, from a settings plugin) is the parent of a build-script classloader
        // whose plugin was compiled against and ships 1.2.0. Classes load parent-first.
        val settingsLoader = IsolatingClassLoader(listOf(guardJar("1.0.0", "settings").toURI().toURL()), javaClass.classLoader, LIBRARY_PREFIXES)
        val buildScriptLoader = URLClassLoader(arrayOf(guardJar("1.2.0", "build-script").toURI().toURL()), settingsLoader)
        val guard = IsolatedGuard(buildScriptLoader)

        assertEquals("1.0.0", guard.loaded)
        val failure = assertFailsWith<GradleException> { guard.requireCompatible(PLUGIN_ID, "1.2.0", "2.0.0") }
        assertFixItMessage(failure, loaded = "1.0.0", range = "[1.2.0, 2.0.0)")
    }

    @Test
    fun `reads the version next to its own class even when a parent classloader exposes another copy`() {
        val loader = IsolatingClassLoader(listOf(guardJar("1.0.0", "own").toURI().toURL()), javaClass.classLoader, LIBRARY_PREFIXES)
        // A plain class-relative lookup is parent-first and finds this build's resource on the test classpath.
        val parentResource = assertNotNull(loader.getResource("io/github/cdsap/pluginsupport/$RESOURCE"))
        assertTrue(parentResource.readText().contains(System.getProperty("pluginSupport.expectedVersion")))

        assertEquals("1.0.0", IsolatedGuard(loader).loaded)
    }

    private fun assertFixItMessage(failure: GradleException, loaded: String, range: String = "[1.1.0, 2.0.0)") {
        val message = assertNotNull(failure.message)
        assertTrue(message.contains("'$PLUGIN_ID'"), message)
        assertTrue(message.contains(range), message)
        assertTrue(message.contains("version $loaded is loaded"), message)
        assertTrue(message.contains("align the cdsap plugin versions or apply them all in settings"), message)
    }

    private fun isolatedGuard(version: String?): IsolatedGuard =
        IsolatedGuard(IsolatingClassLoader(listOf(guardJar(version, version ?: "none").toURI().toURL()), javaClass.classLoader, LIBRARY_PREFIXES))

    private fun guardJar(version: String?, name: String): File {
        val entries = mutableMapOf(CLASS_ENTRY to checkNotNull(PluginSupportVersion::class.java.getResourceAsStream("PluginSupportVersion.class")).readBytes())
        if (version != null) entries["io/github/cdsap/pluginsupport/$RESOURCE"] = "version=$version\n".toByteArray()
        return writeJar(File(tempDir, "plugin-support-$name.jar"), entries)
    }

    private class IsolatedGuard(loader: ClassLoader) {
        private val type = loader.loadClass(PluginSupportVersion::class.java.name)
        private val instance = type.getField("INSTANCE").get(null)

        init {
            check(type !== PluginSupportVersion::class.java) { "guard was not isolated" }
        }

        val loaded: String get() = unwrap { type.getMethod("getLoaded").invoke(instance) as String }

        fun requireCompatible(pluginId: String, minInclusive: String, maxExclusive: String) {
            unwrap {
                type.getMethod("requireCompatible", String::class.java, String::class.java, String::class.java)
                    .invoke(instance, pluginId, minInclusive, maxExclusive)
            }
        }

        private fun <T> unwrap(block: () -> T): T =
            try {
                block()
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    private companion object {
        const val PLUGIN_ID = "io.github.cdsap.example"
        const val RESOURCE = "plugin-support-version.properties"
        const val CLASS_ENTRY = "io/github/cdsap/pluginsupport/PluginSupportVersion.class"
        val LIBRARY_PREFIXES = listOf("io.github.cdsap.pluginsupport.")
    }
}
