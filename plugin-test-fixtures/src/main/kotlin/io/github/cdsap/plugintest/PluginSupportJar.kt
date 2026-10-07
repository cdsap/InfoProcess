package io.github.cdsap.plugintest

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Re-versioned copies of `io.github.cdsap:plugin-support` for testing a plugin's
 * `PluginSupportVersion.requireCompatible` call: the copy reports another loaded version, as when another cdsap plugin
 * brought a different plugin-support onto the build classpath.
 *
 * `GradleRunner.withPluginClasspath` loads the plugin in a classloader that is not a child of the settings
 * classloader, so a copy put on the settings `buildscript` classpath is never seen by a plugin injected that way. To
 * reproduce the settings-first case, put [replaceIn]'s classpath on a build script's `buildscript` classpath instead.
 */
public object PluginSupportJar {
    /** Resource the guard reads the loaded version from. */
    public const val VERSION_RESOURCE: String = "io/github/cdsap/pluginsupport/plugin-support-version.properties"

    /** True for a `plugin-support-<version>.jar`. */
    public fun isPluginSupportJar(file: File): Boolean = file.name.startsWith("plugin-support-") && file.extension == "jar"

    /**
     * Writes a copy of the plugin-support [jar] whose [VERSION_RESOURCE] reports [version] to
     * `<directory>/plugin-support-<version>.jar` and returns it.
     *
     * @throws IllegalStateException when [jar] has no [VERSION_RESOURCE].
     */
    public fun reversioned(jar: File, version: String, directory: File): File {
        val target = File(directory, "plugin-support-$version.jar")
        directory.mkdirs()
        var rewritten = false
        ZipFile(jar).use { source ->
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                for (entry in source.entries()) {
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == VERSION_RESOURCE) {
                        output.write("version=$version\n".toByteArray())
                        rewritten = true
                    } else {
                        source.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }
        }
        check(rewritten) { "$jar has no $VERSION_RESOURCE" }
        return target
    }

    /**
     * [classpath] with its plugin-support jar replaced by a copy reporting [version], written to [directory].
     *
     * @throws IllegalStateException when [classpath] has no plugin-support jar, or more than one.
     */
    public fun replaceIn(classpath: List<File>, version: String, directory: File): List<File> {
        val jars = classpath.filter(::isPluginSupportJar)
        check(jars.size == 1) { "Expected one plugin-support jar on the classpath, found $jars" }
        val copy = reversioned(jars.single(), version, directory)
        return classpath.map { if (it == jars.single()) copy else it }
    }
}
