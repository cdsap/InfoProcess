package io.github.cdsap.infoprocess.collector.kotlin

import io.github.cdsap.infoprocess.core.Component
import io.github.cdsap.infoprocess.core.ProcessObservation

object KotlinProcessClassifier {
    fun classify(pid: Long, mainClass: String, arguments: String = ""): ProcessObservation? {
        if (!mainClass.contains("kotlin.daemon.KotlinCompileDaemon")) return null
        return ProcessObservation(Component.KOTLIN, pid, mainClass, attributes = mapOf("argumentsPresent" to (!arguments.isNullOrBlank()).toString()))
    }
}

