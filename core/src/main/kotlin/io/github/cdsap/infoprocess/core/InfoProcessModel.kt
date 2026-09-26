package io.github.cdsap.infoprocess.core

enum class Component { GRADLE, KOTLIN, TEST, GC }

enum class ComponentStatus { DISABLED, UNAVAILABLE, NO_DATA, SUCCESS, FAILURE }

data class Diagnostic(val component: Component, val message: String)

data class ProcessObservation(
    val component: Component,
    val pid: Long,
    val command: String,
    val heapUsedBytes: Long? = null,
    val heapCommittedBytes: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class GcObservation(
    val source: String,
    val collector: String,
    val pauseMillis: Double,
    val reclaimedBytes: Long? = null,
)

data class ComponentReport(
    val status: ComponentStatus,
    val processObservations: List<ProcessObservation> = emptyList(),
    val gcObservations: List<GcObservation> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

data class InfoProcessReport(
    val components: Map<Component, ComponentReport>,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val processes: List<ProcessObservation> = components.values.flatMap { it.processObservations }
    val gc: List<GcObservation> = components.values.flatMap { it.gcObservations }
}

