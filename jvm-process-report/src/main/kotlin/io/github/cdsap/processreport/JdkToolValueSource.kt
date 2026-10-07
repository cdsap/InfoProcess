package io.github.cdsap.processreport

import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import javax.inject.Inject

/**
 * The `jstat`/`jinfo` pipelines of `io.github.cdsap:commandline-value-source` 0.1.0. jdk-tools-parser parses their
 * output, so the strings must stay byte-for-byte identical to that release.
 */
internal object JdkToolCommands {
    fun jStat(processName: String): String =
        "jps | grep $processName | sed 's/$processName//' | while read ln; do  jstat -gc -t \$ln; echo \"\$ln\"; done"

    fun jInfo(processName: String): String =
        "jps | grep $processName | sed 's/$processName//' | while read ln; do  jinfo \$ln  | grep \"XX:MaxHeapSize\"; echo \"\$ln\";  done"

    fun provider(providers: ProviderFactory, command: String): Provider<String> =
        providers.of(JdkToolCommandValueSource::class.java) { it.parameters.command.set(command) }
}

/** Runs `sh -c <command>` and returns its standard output, or "" when the command is empty or fails. */
internal abstract class JdkToolCommandValueSource : ValueSource<String, JdkToolCommandValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val command: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val command = parameters.command.orNull
        if (command.isNullOrBlank()) return ""
        val output = ByteArrayOutputStream()
        return try {
            execOperations.exec { spec ->
                spec.commandLine("sh", "-c", command)
                spec.standardOutput = output
                spec.errorOutput = ByteArrayOutputStream()
            }
            String(output.toByteArray(), Charset.defaultCharset())
        } catch (_: GradleException) {
            // A non-zero exit or a command that cannot start; Gradle reports both as GradleException subtypes.
            ""
        }
    }
}
