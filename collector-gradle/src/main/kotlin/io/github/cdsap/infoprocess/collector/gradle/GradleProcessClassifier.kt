package io.github.cdsap.infoprocess.collector.gradle

import io.github.cdsap.infoprocess.core.Component
import io.github.cdsap.infoprocess.core.ProcessObservation

object GradleProcessClassifier {
    private val gradleNames = setOf("org.gradle.launcher.daemon.bootstrap.GradleDaemon", "org.gradle.launcher.daemon.bootstrap.GradleDaemon")

    fun classify(pid: Long, mainClass: String, arguments: String = ""): ProcessObservation? {
        if (mainClass !in gradleNames && !mainClass.contains("GradleDaemon")) return null
        return ProcessObservation(Component.GRADLE, pid, mainClass, attributes = mapOf("argumentsPresent" to (!arguments.isNullOrBlank()).toString()))
    }
}

