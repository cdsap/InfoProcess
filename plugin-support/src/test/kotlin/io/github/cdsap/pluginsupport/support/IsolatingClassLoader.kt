package io.github.cdsap.pluginsupport.support

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Loads classes under [isolatedPrefixes] only from its own [urls], never from [parent]; every other class (Gradle
 * API, Kotlin stdlib) and every resource lookup delegates to [parent] first, like a regular classloader.
 */
class IsolatingClassLoader(
    urls: List<URL>,
    parent: ClassLoader,
    private val isolatedPrefixes: List<String>,
) : URLClassLoader(urls.toTypedArray(), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (isolatedPrefixes.none { name.startsWith(it) }) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val type = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(type)
            return type
        }
    }
}

fun writeJar(target: File, entries: Map<String, ByteArray>): File {
    JarOutputStream(target.outputStream()).use { jar ->
        entries.forEach { (name, bytes) ->
            jar.putNextEntry(JarEntry(name))
            jar.write(bytes)
            jar.closeEntry()
        }
    }
    return target
}
