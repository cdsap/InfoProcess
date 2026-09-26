package io.github.cdsap.infoprocess.plugin

import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

internal fun Property<Boolean>.conventionFromBooleanProperty(
    propertyName: String,
    properties: ProviderFactoryAccess,
    defaultValue: Boolean,
) {
    val raw = properties.value(propertyName)
    if (raw == null) {
        convention(defaultValue)
    } else {
        val parsed = raw.lowercase().toBooleanStrictOrNull()
            ?: throw GradleException("Invalid boolean value '$raw' for Gradle property '$propertyName'; expected true or false")
        convention(parsed)
    }
}

internal interface ProviderFactoryAccess { fun value(name: String): String? }

internal class MapProviderFactoryAccess(private val values: Map<String, String>) : ProviderFactoryAccess {
    override fun value(name: String): String? = values[name]
}
