package io.github.cdsap.pluginsupport

import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener

/**
 * Base for a plugin's end-of-build console report: a shared build service that [PluginEntry] registers under the
 * plugin's service name and subscribes to task completion events, so Gradle instantiates it during task execution
 * and closes it when the build finishes. [report] runs from [close] unless [Parameters.consoleEnabled] is false.
 *
 * ```
 * abstract class MyConsoleService : ConsoleReportService<MyConsoleService.Params>() {
 *     interface Params : ConsoleReportService.Parameters {
 *         val logs: ListProperty<String>
 *     }
 *
 *     override fun report() { ... }
 * }
 * ```
 */
public abstract class ConsoleReportService<P : ConsoleReportService.Parameters> :
    BuildService<P>,
    OperationCompletionListener,
    AutoCloseable {
    public interface Parameters : BuildServiceParameters {
        /** Set by [PluginEntry] from the console policy; an unset value counts as true. */
        public val consoleEnabled: Property<Boolean>
    }

    override fun onFinish(event: FinishEvent) {}

    final override fun close() {
        if (parameters.consoleEnabled.getOrElse(true)) {
            report()
        }
    }

    /** Prints or writes the console report at the end of the build. */
    protected abstract fun report()
}
