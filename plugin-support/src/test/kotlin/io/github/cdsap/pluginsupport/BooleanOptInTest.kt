package io.github.cdsap.pluginsupport

import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BooleanOptInTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `absent property and unset DSL give the default`() {
        assertEquals(false, resolve(property = null, dsl = null, default = false))
        assertEquals(true, resolve(property = null, dsl = null, default = true))
    }

    @Test
    fun `property sets the convention when the DSL is unset`() {
        assertEquals(true, resolve(property = "true", dsl = null, default = false))
        assertEquals(false, resolve(property = "false", dsl = null, default = true))
    }

    @Test
    fun `DSL value wins over the property`() {
        assertEquals(false, resolve(property = "true", dsl = false, default = false))
        assertEquals(true, resolve(property = "false", dsl = true, default = false))
        assertEquals(true, resolve(property = null, dsl = true, default = false))
    }

    @Test
    fun `property values use toBoolean semantics`() {
        assertEquals(true, resolve(property = "TRUE", dsl = null, default = false))
        assertEquals(false, resolve(property = "yes", dsl = null, default = true))
        assertEquals(false, resolve(property = "1", dsl = null, default = true))
        assertEquals(false, resolve(property = "", dsl = null, default = true))
    }

    @Test
    fun `provider default is followed while the property is absent`() {
        val project = project(property = null)
        val fallback = project.objects.property(Boolean::class.java).value(true)
        val property = project.objects.property(Boolean::class.java)
        BooleanOptIn.convention(property, project.providers, NAME, fallback)

        assertEquals(true, property.get())
        fallback.set(false)
        assertEquals(false, property.get())

        val withProperty = project(property = "true")
        val overridden = withProperty.objects.property(Boolean::class.java)
        BooleanOptIn.convention(overridden, withProperty.providers, NAME, withProperty.provider { false })
        assertEquals(true, overridden.get())
    }

    @Test
    fun `gradleProperty is absent when the property is not set`() {
        assertNull(BooleanOptIn.gradleProperty(project(property = null).providers, NAME).orNull)
        assertFalse(BooleanOptIn.gradleProperty(project(property = "nope").providers, NAME).get())
    }

    private fun resolve(property: String?, dsl: Boolean?, default: Boolean): Boolean {
        val project = project(property)
        val value: Property<Boolean> = project.objects.property(Boolean::class.java)
        BooleanOptIn.convention(value, project.providers, NAME, default)
        if (dsl != null) value.set(dsl)
        return value.get()
    }

    private fun project(property: String?): Project {
        val dir = File(tempDir, "project-${counter++}").apply { mkdirs() }
        if (property != null) File(dir, "gradle.properties").writeText("$NAME=$property\n")
        return ProjectBuilder.builder().withProjectDir(dir).build()
    }

    private var counter = 0

    private companion object {
        const val NAME = "example.feature.enabled"
    }
}
