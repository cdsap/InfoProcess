package io.github.cdsap.processreport

import io.github.cdsap.pluginsupport.ConsoleReportService
import org.gradle.api.provider.Property

/**
 * Console build service for `ReportingPluginSpec.consoleServiceType`: at the end of the build it collects the
 * daemons and prints [ProcessReport.consoleTable], or nothing when there are none.
 */
public abstract class ProcessConsoleService : ConsoleReportService<ProcessConsoleService.Params>() {
    public interface Params : ConsoleReportService.Parameters {
        public val spec: Property<ProcessReportSpec>

        /** Usually [ProcessReport.jStat]. */
        public val jStat: Property<String>

        /** Usually [ProcessReport.jInfo]. */
        public val jInfo: Property<String>
    }

    override fun report() {
        val spec = parameters.spec.get()
        val processes = ProcessReport.collect(spec, parameters.jStat.getOrElse(""), parameters.jInfo.getOrElse(""))
        if (processes.isNotEmpty()) {
            println(ProcessReport.consoleTable(spec, processes))
        }
    }
}
