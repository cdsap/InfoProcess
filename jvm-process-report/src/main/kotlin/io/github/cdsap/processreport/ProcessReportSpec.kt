package io.github.cdsap.processreport

import io.github.cdsap.jdk.tools.parser.model.TypeProcess
import java.io.Serializable

/**
 * What one plugin reports: which JVM daemons to look for and how its console table and Build Scan values are named.
 * Each plugin owns its spec, so its published contract (title, key prefix, GBOS producer) stays in the plugin.
 *
 * ```
 * val GRADLE_PROCESSES = ProcessReportSpec(
 *     processName = "GradleDaemon",
 *     typeProcess = TypeProcess.Gradle,
 *     consoleTitle = "Gradle processes",
 *     keyPrefix = "Gradle-Process",
 *     gbos = null,
 * )
 * ```
 *
 * @param processName main class name as listed by `jps`, used to select the daemons (`GradleDaemon`,
 *   `KotlinCompileDaemon`). Letters, digits, `_` and `.` only, because it becomes part of a shell command.
 * @param typeProcess the type given to every collected [io.github.cdsap.jdk.tools.parser.model.Process].
 * @param consoleTitle first row of the console table.
 * @param keyPrefix custom-value key prefix: values are written as `<keyPrefix>-<pid>-max` and so on. The prefix
 *   always comes from the spec, never from `Process.typeProcess`.
 * @param gbos GBOS producer used when the plugin's GBOS opt-in is enabled, or null when the plugin has none.
 */
public class ProcessReportSpec(
    public val processName: String,
    public val typeProcess: TypeProcess,
    public val consoleTitle: String,
    public val keyPrefix: String,
    public val gbos: GbosProducerSpec?,
) : Serializable {
    init {
        require(processName.isNotEmpty() && processName.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
            "processName must only contain letters, digits, '_' and '.': '$processName'"
        }
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
    }
}

/**
 * GBOS producer identity of a plugin. With GBOS enabled, the Build Scan gets `gbos.schema` = [schemaVersion],
 * `gbos.v1.producer.<slug>.version` = [producerVersion], `gbos.v1.producer.<slug>.name` = [producerName] and one
 * `jvm.process` observation per daemon whose `jvm.process.role` attribute is [processRole].
 *
 * @param schemaVersion GBOS schema version written to `gbos.schema`, for example `1.0.0`.
 * @param producerName producer name, for example `info-kotlin-process`; its slug (`-` and `.` become `_`) names the keys.
 * @param producerVersion GBOS contract version the producer emits, for example `0.0.4`.
 * @param processRole value of the `jvm.process.role` attribute, for example `kotlin-daemon`.
 */
public class GbosProducerSpec(
    public val schemaVersion: String,
    public val producerName: String,
    public val producerVersion: String,
    public val processRole: String,
) : Serializable
