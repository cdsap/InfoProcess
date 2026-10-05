package io.github.cdsap.plugintest

import java.io.File
import java.util.Properties

/**
 * TestKit classpaths for the plugin under test, built from the `plugin-under-test-metadata.properties` resource that
 * the `java-gradle-plugin` plugin writes for `GradleRunner.withPluginClasspath()`.
 */
public object PluginUnderTest {
    /** Name of the resource written by the `pluginUnderTestMetadata` task. */
    public const val METADATA_RESOURCE: String = "plugin-under-test-metadata.properties"

    /**
     * The `implementation-classpath` of [METADATA_RESOURCE], loaded from [loader]: what `withPluginClasspath()` uses.
     *
     * @throws IllegalStateException when the resource is not on [loader]'s classpath.
     */
    @JvmOverloads
    public fun classpath(loader: ClassLoader = PluginUnderTest::class.java.classLoader): List<File> {
        val metadata = Properties()
        val stream = checkNotNull(loader.getResourceAsStream(METADATA_RESOURCE)) {
            "$METADATA_RESOURCE is not on the test classpath; apply the java-gradle-plugin plugin to the plugin build."
        }
        stream.use { metadata.load(it) }
        return metadata.getProperty("implementation-classpath").split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
    }

    /**
     * [pluginClasspath] for `withPluginClasspath`, with the fake Develocity first: this library's jar (the fake
     * `com.gradle.develocity` and `com.gradle.enterprise` plugins), then [FakeDevelocity.develocityApiJar] written to
     * [apiJarDirectory], then [pluginClasspath] without any real Develocity or Gradle Enterprise plugin jar. The
     * plugin under test then shares the fakes' API classes, as with a settings-applied Develocity.
     */
    @JvmOverloads
    public fun withFakeDevelocity(apiJarDirectory: File, pluginClasspath: List<File> = classpath()): List<File> {
        val fixtures = codeSource(FakeDevelocityPlugin::class.java)
        val apiJar = FakeDevelocity.develocityApiJar(apiJarDirectory)
        return (listOf(fixtures, apiJar) + pluginClasspath.filterNot(::isRealDevelocityPlugin)).distinct()
    }

    /** True for a `develocity-gradle-plugin` or `gradle-enterprise-gradle-plugin` jar, but not their adapters. */
    public fun isRealDevelocityPlugin(file: File): Boolean {
        val name = file.name
        if (!name.endsWith(".jar") || name.startsWith("develocity-gradle-plugin-adapters")) return false
        return name.startsWith("develocity-gradle-plugin-") || name.startsWith("gradle-enterprise-gradle-plugin-")
    }
}
