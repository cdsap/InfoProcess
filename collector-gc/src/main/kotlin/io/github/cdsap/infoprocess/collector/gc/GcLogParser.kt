package io.github.cdsap.infoprocess.collector.gc

import io.github.cdsap.infoprocess.core.GcObservation
import java.io.File

class GcLogParser {
    private val pausePattern = Regex("(?:Pause|pause)[^0-9]*(\\d+(?:\\.\\d+)?)ms")

    fun parse(file: File): ParseResult {
        if (!file.isFile) return ParseResult(emptyList(), ignoredLines = 0, missing = true)
        var ignored = 0
        val observations = buildList {
            file.forEachLine { line ->
                val pause = pausePattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                if (pause == null) ignored++ else add(GcObservation(file.name, collectorName(line), pause))
            }
        }
        return ParseResult(observations, ignored, missing = false)
    }

    private fun collectorName(line: String): String = when {
        "G1" in line -> "G1"
        "Parallel" in line -> "Parallel"
        else -> "unknown"
    }
}

data class ParseResult(val observations: List<GcObservation>, val ignoredLines: Int, val missing: Boolean)

