package io.github.cdsap.plugintest

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests, plus TestKit builds for the `withPluginClasspath` mode; `integration-tests` drives the fakes and
 * helpers through plugin resolution from a Maven repository on every Gradle lane.
 */
class PluginTestFixturesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `settings script orders the blocks and applies the local repository convention`() {
        val repo = File(dir, "repo")
        val apiJar = File(dir, "api.jar")

        val script =
            SettingsScript()
                .append("println 'body'")
                .include("sub")
                .rootProjectName("consumer")
                .plugin(FakeDevelocity.PLUGIN_ID)
                .plugin("io.github.cdsap.fixture", "1.0")
                .buildscriptClasspath("io.github.cdsap:plugin-support:2.0.0")
                .fakeDevelocity("0.1.0", apiJar)
                .localMavenRepository(repo)
                .render()

        val repoLine = "maven { url = uri('${repo.absolutePath}') }"
        assertEquals(
            """
            pluginManagement {
                repositories {
                    $repoLine
                    gradlePluginPortal()
                    mavenCentral()
                }
                resolutionStrategy {
                    eachPlugin {
                        if (requested.id.id in ['com.gradle.develocity', 'com.gradle.enterprise']) {
                            useModule('io.github.cdsap:plugin-test-fixtures:0.1.0')
                        }
                    }
                }
            }
            buildscript {
                repositories {
                    $repoLine
                    gradlePluginPortal()
                    mavenCentral()
                }
                dependencies {
                    classpath 'io.github.cdsap:plugin-support:2.0.0'
                    classpath files('${apiJar.absolutePath}')
                }
            }
            plugins {
                id 'com.gradle.develocity'
                id 'io.github.cdsap.fixture' version '1.0'
            }
            dependencyResolutionManagement {
                repositories {
                    $repoLine
                    mavenCentral()
                }
            }
            rootProject.name = 'consumer'
            include 'sub'
            println 'body'

            """.trimIndent(),
            script,
        )
    }

    @Test
    fun `an empty settings script renders nothing and build scripts only render what was asked for`() {
        assertEquals("", SettingsScript().render())
        assertEquals("plugins {\n    id 'x' version '1'\n}\ntasks.register('t')\n", BuildScript().plugin("x", "1").append("tasks.register('t')").render())
        assertTrue(BuildScript().localMavenRepository(dir).render().isEmpty(), "repositories are only needed for classpath entries")
    }

    @Test
    fun `groovy strings escape quotes and backslashes`() {
        assertEquals("'it\\'s C:\\\\dir'", groovyString("it's C:\\dir"))
    }

    @Test
    fun `scan values and tags are parsed from the fake's output in order`() {
        val output = "> Task :help\nSCAN-VALUE a=1\nSCAN-TAG t\nnoise SCAN-VALUE x=y\nSCAN-VALUE b=x=y\n"

        assertEquals(listOf("a=1", "b=x=y"), FakeDevelocity.scanValues(output))
        assertEquals(listOf("t"), FakeDevelocity.scanTags(output))
    }

    @Test
    fun `the develocity api jar keeps the api and drops the real plugin descriptors`() {
        val jar = FakeDevelocity.develocityApiJar(File(dir, "api"))

        ZipFile(jar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("com/gradle/develocity/agent/gradle/DevelocityConfiguration.class" in names)
            assertTrue("com/gradle/enterprise/gradleplugin/GradleEnterpriseExtension.class" in names)
            assertFalse(names.any { it.startsWith("META-INF/gradle-plugins/") }, names.filter { it.startsWith("META-INF/") }.toString())
            assertEquals(names.size, names.toSet().size, "no duplicate entries")
        }
        assertEquals(jar, FakeDevelocity.develocityApiJar(File(dir, "api")), "reused once written")
    }

    @Test
    fun `the init script applies the fake from an isolated classpath`() {
        val apiJar = File(dir, "api.jar")

        val script = FakeDevelocity.writeInitScript(File(dir, "init/fake.gradle"), apiJar).readText()

        assertTrue(script.contains("classpath files('${codeSource(FakeDevelocityPlugin::class.java).absolutePath}', '${apiJar.absolutePath}')"), script)
        assertTrue(script.contains("settings.pluginManager.apply(io.github.cdsap.plugintest.FakeDevelocityPlugin)"), script)
    }

    @Test
    fun `with an injected plugin classpath the fakes record values, tags and build finished actions`() {
        val project = File(dir, "develocity")
        SettingsScript()
            .plugin(FakeDevelocity.PLUGIN_ID)
            .append("develocity.buildScan { it.value('k', 'v'); it.tag('t'); it.buildFinished { develocity.buildScan.tag('finished') } }")
            .writeTo(project)

        val develocity = runWithInjectedClasspath(project)

        assertTrue(develocity.contains(FakeDevelocity.APPLIED_MARKER), develocity)
        assertEquals(listOf("k=v"), FakeDevelocity.scanValues(develocity), develocity)
        assertEquals(listOf("t", "finished"), FakeDevelocity.scanTags(develocity), develocity)

        val legacyProject = File(dir, "legacy")
        SettingsScript()
            .plugin(FakeDevelocity.LEGACY_PLUGIN_ID)
            .append("gradleEnterprise.buildScan { it.value('legacy', 'v') }")
            .writeTo(legacyProject)

        val legacy = runWithInjectedClasspath(legacyProject)

        assertTrue(legacy.contains(FakeDevelocity.LEGACY_APPLIED_MARKER), legacy)
        assertEquals(listOf("legacy=v"), FakeDevelocity.scanValues(legacy), legacy)
    }

    @Test
    fun `the quiet property drops the applied markers`() {
        val project = File(dir, "quiet")
        SettingsScript().plugin(FakeDevelocity.PLUGIN_ID).writeTo(project)

        val output = runWithInjectedClasspath(project, "-P${FakeDevelocity.QUIET_PROPERTY}=true")

        assertFalse(output.contains(FakeDevelocity.APPLIED_MARKER), output)
    }

    @Test
    fun `the fake replays build finished actions with isolated projects`() {
        val project = File(dir, "isolated")
        SettingsScript()
            .plugin(FakeDevelocity.PLUGIN_ID)
            .include("sub")
            .append("def scan = develocity.buildScan\nscan.buildFinished { scan.tag('finished') }")
            .writeTo(project)
        File(project, "sub").mkdirs()
        File(project, "sub/build.gradle").writeText("tasks.register('subTask')\n")

        val runs = IsolatedProjects.storeAndReuse(runner(project), ":sub:subTask", "--stacktrace")

        assertEquals(listOf("finished"), FakeDevelocity.scanTags(runs.store.output), runs.store.output)
        assertEquals(listOf("finished"), FakeDevelocity.scanTags(runs.reuse.output), runs.reuse.output)
    }

    @Test
    fun `the fake develocity classpath puts the fake first and drops the real plugin but not the adapters`() {
        val real = File(dir, "develocity-gradle-plugin-4.6.0.jar")
        val legacy = File(dir, "gradle-enterprise-gradle-plugin-3.16.jar")
        val adapters = File(dir, "develocity-gradle-plugin-adapters-1.2.1.jar")
        val plugin = File(dir, "my-plugin.jar")

        val classpath = PluginUnderTest.withFakeDevelocity(File(dir, "api"), listOf(plugin, real, adapters, legacy))

        assertEquals(codeSource(FakeDevelocityPlugin::class.java), classpath[0])
        assertEquals(FakeDevelocity.develocityApiJar(File(dir, "api")), classpath[1])
        assertEquals(listOf(plugin, adapters), classpath.drop(2))
    }

    @Test
    fun `plugin support jars are re-versioned in place on a classpath`() {
        val original = File(dir, "plugin-support-0.1.0.jar")
        ZipOutputStream(original.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(PluginSupportJar.VERSION_RESOURCE))
            zip.write("version=0.1.0\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("io/github/cdsap/pluginsupport/Other.class"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val other = File(dir, "other.jar")

        val classpath = PluginSupportJar.replaceIn(listOf(other, original), "2.0.0", File(dir, "reversioned"))

        assertEquals(listOf(other, File(dir, "reversioned/plugin-support-2.0.0.jar")), classpath)
        ZipFile(classpath[1]).use { zip ->
            assertEquals("version=2.0.0\n", zip.getInputStream(zip.getEntry(PluginSupportJar.VERSION_RESOURCE)).readBytes().decodeToString())
            assertEquals(listOf<Byte>(1, 2, 3), zip.getInputStream(zip.getEntry("io/github/cdsap/pluginsupport/Other.class")).readBytes().toList())
        }
        assertFailsWith<IllegalStateException> { PluginSupportJar.replaceIn(listOf(other), "2.0.0", dir) }
    }

    private fun runWithInjectedClasspath(project: File, vararg extraArgs: String): String =
        runner(project, "help", *extraArgs).build().output

    private fun runner(project: File, vararg args: String): GradleRunner {
        val descriptor = checkNotNull(javaClass.classLoader.getResource("META-INF/gradle-plugins/com.gradle.develocity.properties"))
        val resources = File(descriptor.toURI()).parentFile.parentFile.parentFile
        val classpath = listOf(codeSource(FakeDevelocityPlugin::class.java), resources, FakeDevelocity.develocityApiJar(File(dir, "api")))
        return GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath(classpath.distinct())
            .withArguments(args.toList() + "--stacktrace")
    }
}
