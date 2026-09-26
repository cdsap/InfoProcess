package io.github.cdsap.infoprocess.core

class InfoProcessAggregator {
    private val reports = linkedMapOf<Component, ComponentReport>()

    @Synchronized
    fun record(component: Component, report: ComponentReport) {
        reports[component] = report
    }

    @Synchronized
    fun snapshot(): InfoProcessReport = InfoProcessReport(reports.toMap())
}

