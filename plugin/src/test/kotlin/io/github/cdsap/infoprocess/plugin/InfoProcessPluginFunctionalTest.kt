package io.github.cdsap.infoprocess.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class InfoProcessPluginFunctionalTest {
    @Test
    fun `settings plugin writes a local summary`() {
        val projectDir = Files.createTempDirectory("info-process-test").toFile()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
            plugins { id("io.github.cdsap.infoprocess") }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText("tasks.register(\"verifyFixture\")")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("verifyFixture", "--stacktrace")
            .forwardOutput()
            .build()

        assertTrue(result.task(":verifyFixture")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE))
        assertTrue(projectDir.resolve("build/reports/info-process/summary.json").isFile)
    }
}
