package io.github.cdsap.infoprocess.plugin

import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BooleanPropertySupportTest {
    @Test
    fun `accepts case insensitive boolean values`() {
        val property = ProjectBuilder.builder().build().objects.property(Boolean::class.java)
        property.conventionFromBooleanProperty("infoProcess.enabled", MapProviderFactoryAccess(mapOf("infoProcess.enabled" to "TrUe")), false)
        assertEquals(true, property.get())
    }

    @Test
    fun `rejects invalid boolean values with property context`() {
        val property = ProjectBuilder.builder().build().objects.property(Boolean::class.java)
        val error = assertFailsWith<GradleException> {
            property.conventionFromBooleanProperty("infoProcess.enabled", MapProviderFactoryAccess(mapOf("infoProcess.enabled" to "yes")), true)
        }
        assertEquals("Invalid boolean value 'yes' for Gradle property 'infoProcess.enabled'; expected true or false", error.message)
    }
}

