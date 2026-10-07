package io.github.cdsap.fixture.settingsonly

import io.github.cdsap.gbos.core.GbosAttributeValue
import io.github.cdsap.gbos.core.GbosCustomValueSink
import io.github.cdsap.gbos.core.GbosDevelocity
import io.github.cdsap.gbos.core.GbosIndex
import io.github.cdsap.gbos.core.GbosMeasurement
import io.github.cdsap.gbos.core.GbosObservation
import io.github.cdsap.gbos.core.GbosProducer
import io.github.cdsap.pluginsupport.BooleanOptIn
import io.github.cdsap.pluginsupport.BuildScanReporter
import io.github.cdsap.pluginsupport.BuildScanSink
import io.github.cdsap.pluginsupport.ConsoleReportService
import io.github.cdsap.pluginsupport.DevelocityBridge
import io.github.cdsap.pluginsupport.PluginSupportVersion
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.util.GradleVersion
import javax.inject.Inject

private const val PLUGIN_ID = "io.github.cdsap.fixture.settings-only"
private const val SERVICE_NAME = "settingsOnlyFixtureService"

/**
 * Fixture shaped like InfoTestProcess: a settings-only plugin that does not use `PluginEntry`. It calls the version
 * guard itself (built against plugin-support 1.0.0, range [1.0.0, 2.0.0)), wires everything from the settings through
 * `DevelocityBridge`, always prints its console line, and with `settingsOnly.gbos.enabled=true` replaces its legacy
 * Build Scan values with GBOS ones written by build-observability-core's `GbosDevelocity` publisher.
 *
 * `fixture.echoReporter=true` makes the reporter print every value and tag it writes (`REPORTED-VALUE` /
 * `REPORTED-TAG`), which shows what reached a real Develocity extension.
 */
abstract class SettingsOnlyFixturePlugin @Inject constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Settings> {
    override fun apply(settings: Settings) {
        PluginSupportVersion.requireCompatible(PLUGIN_ID, "1.0.0", "2.0.0")
        println(
            "SETTINGS-ONLY applied plugin-support=${PluginSupportVersion.loaded} " +
                "gradle=${GradleVersion.current().version} kotlin-stdlib=${KotlinVersion.CURRENT}",
        )
        val gbos = BooleanOptIn.gradleProperty(settings.providers, "settingsOnly.gbos.enabled").getOrElse(false)
        val echo = BooleanOptIn.gradleProperty(settings.providers, "fixture.echoReporter").getOrElse(false)
        DevelocityBridge.detect(settings, false) { rootProject, develocity ->
            val service = rootProject.gradle.sharedServices.registerIfAbsent(SERVICE_NAME, SettingsOnlyConsoleService::class.java) {}
            registry.onTaskCompletion(service)
            develocity?.onBuildFinished(SettingsOnlyReporter(gbos, echo))
        }
    }
}

abstract class SettingsOnlyConsoleService : ConsoleReportService<ConsoleReportService.Parameters>() {
    override fun report() {
        println("SETTINGS-ONLY-CONSOLE tests=3 failed=0")
    }
}

class SettingsOnlyReporter(private val gbos: Boolean, private val echo: Boolean) : BuildScanReporter {
    override fun report(sink: BuildScanSink) {
        val target = if (echo) EchoSink(sink) else sink
        if (gbos) {
            GbosDevelocity.publish(
                GbosCustomValueSink { key, value -> target.value(key, value) },
                schemaVersion = "1.0.0",
                producerName = "settings-only-fixture",
                producerVersion = "0.0.4",
                observations = observations(),
                indexes = listOf(GbosIndex("test.count", "sum")),
            )
        } else {
            target.value("settingsOnly.tests", "3")
            target.value("settingsOnly.failed", "0")
        }
        target.tag("tests:passed")
    }

    private fun observations(): List<GbosObservation> {
        val producer = GbosProducer("settings-only-fixture", "0.0.4")
        val count = listOf(GbosMeasurement("test.count", 3.0, "count", "sum"))
        return listOf(
            GbosObservation(
                schemaVersion = "1.0.0",
                producer = producer,
                scope = "test.task",
                aggregationScope = "entity",
                attributes = mapOf("test.task.path" to GbosAttributeValue.Text(":test")),
                measurements = count,
            ),
            GbosObservation(
                schemaVersion = "1.0.0",
                producer = producer,
                scope = "build",
                aggregationScope = "build",
                attributes = emptyMap(),
                measurements = count,
            ),
        )
    }
}

class EchoSink(private val delegate: BuildScanSink) : BuildScanSink {
    override fun value(key: String, value: String) {
        println("REPORTED-VALUE $key=$value")
        delegate.value(key, value)
    }

    override fun tag(name: String) {
        println("REPORTED-TAG $name")
        delegate.tag(name)
    }
}
