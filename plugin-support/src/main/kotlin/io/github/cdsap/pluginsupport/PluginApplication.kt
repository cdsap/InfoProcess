package io.github.cdsap.pluginsupport

/**
 * Where the plugin application that configured the build was applied. Compare with the constants; new kinds may be
 * added.
 */
public class PluginApplication private constructor(private val name: String) {
    override fun equals(other: Any?): Boolean = other is PluginApplication && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    public companion object {
        /** Applied in a settings script (or an init script's `beforeSettings`). */
        @JvmField
        public val SETTINGS: PluginApplication = PluginApplication("SETTINGS")

        /** Applied in the root project's build script. */
        @JvmField
        public val ROOT_PROJECT: PluginApplication = PluginApplication("ROOT_PROJECT")

        /** Applied in a subproject's build script. */
        @JvmField
        public val SUBPROJECT: PluginApplication = PluginApplication("SUBPROJECT")
    }
}
