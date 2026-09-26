package io.github.cdsap.infoprocess.collector.test

import java.util.concurrent.ConcurrentHashMap

data class WorkerSnapshot(val workerId: String, val pid: Long, val taskPath: String, val heapUsedBytes: Long)

class TestWorkerRegistry {
    private val workers = ConcurrentHashMap<String, WorkerSnapshot>()

    fun record(snapshot: WorkerSnapshot) { workers[snapshot.workerId] = snapshot }

    fun snapshot(): List<WorkerSnapshot> = workers.values.sortedBy { it.workerId }

    fun clear() { workers.clear() }
}

