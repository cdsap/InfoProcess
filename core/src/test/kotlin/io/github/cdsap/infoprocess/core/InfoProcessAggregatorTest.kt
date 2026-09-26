package io.github.cdsap.infoprocess.core

import kotlin.test.Test
import kotlin.test.assertEquals

class InfoProcessAggregatorTest {
    @Test
    fun `replacing a component keeps the report deterministic`() {
        val aggregator = InfoProcessAggregator()
        aggregator.record(Component.GRADLE, ComponentReport(ComponentStatus.SUCCESS, listOf(ProcessObservation(Component.GRADLE, 7, "GradleDaemon"))))
        aggregator.record(Component.GRADLE, ComponentReport(ComponentStatus.NO_DATA))

        assertEquals(ComponentStatus.NO_DATA, aggregator.snapshot().components[Component.GRADLE]?.status)
        assertEquals(emptyList(), aggregator.snapshot().processes)
    }
}

