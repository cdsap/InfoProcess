package io.github.cdsap.pluginsupport

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * Boolean opt-ins backed by a Gradle property and a DSL property, with the precedence the cdsap plugins use:
 * an absent Gradle property falls back to the default, a present one sets the convention, and a value set in the
 * DSL wins over both. Gradle property values are read with [String.toBoolean], so anything but `true` (ignoring
 * case) is false rather than an error.
 */
public object BooleanOptIn {
    /** The Gradle property [name] as a boolean; absent when the property is not set. */
    public fun gradleProperty(providers: ProviderFactory, name: String): Provider<Boolean> =
        providers.gradleProperty(name).map { it.toBoolean() }

    /** Sets the convention of [property] to the Gradle property [name], or [default] when it is not set. */
    public fun convention(property: Property<Boolean>, providers: ProviderFactory, name: String, default: Boolean) {
        property.convention(gradleProperty(providers, name).orElse(default))
    }

    /** Sets the convention of [property] to the Gradle property [name], or the value of [default] when it is not set. */
    public fun convention(property: Property<Boolean>, providers: ProviderFactory, name: String, default: Provider<Boolean>) {
        property.convention(gradleProperty(providers, name).orElse(default))
    }
}
