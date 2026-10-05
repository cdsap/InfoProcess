package io.github.cdsap.plugintest

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests, plus one TestKit build for the `withPluginClasspath` mode; `integration-tests` drives the fakes and
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

    private fun runWithInjectedClasspath(project: File): String {
        val descriptor = checkNotNull(javaClass.classLoader.getResource("META-INF/gradle-plugins/com.gradle.develocity.properties"))
        val resources = File(descriptor.toURI()).parentFile.parentFile.parentFile
        val classpath = listOf(codeSource(FakeDevelocityPlugin::class.java), resources, FakeDevelocity.develocityApiJar(File(dir, "api")))
        return GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath(classpath.distinct())
            .withArguments("help", "--stacktrace")
            .build()
            .output
    }
}
