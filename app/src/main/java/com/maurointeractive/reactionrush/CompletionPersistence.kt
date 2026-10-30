package com.maurointeractive.reactionrush

import android.annotation.SuppressLint
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class SaveStatus {
    IDLE,
    SAVING,
    SAVED,
    FAILED
}

/**
 * Confirms preceding preference apply() writes on an executor, without replaying gameplay.
 * The injected executor must dispatch work off the caller's thread in production.
 */
class CompletionPersistence(
    private val executor: Executor,
    private val commitAction: () -> Boolean
) {
    private val lock = Any()
    private val mutableStatus = MutableStateFlow(SaveStatus.IDLE)
    val status: StateFlow<SaveStatus> = mutableStatus.asStateFlow()
    private var generation = 0L
    private var workerQueued = false

    fun checkpoint() = requestCheckpoint(onlyIfFailed = false)

    fun retry() = requestCheckpoint(onlyIfFailed = true)

    private fun requestCheckpoint(onlyIfFailed: Boolean) {
        val enqueue = synchronized(lock) {
            if (onlyIfFailed && mutableStatus.value != SaveStatus.FAILED) return
            generation++
            mutableStatus.value = SaveStatus.SAVING
            if (workerQueued) false else {
                workerQueued = true
                true
            }
        }
        if (!enqueue) return
        try {
            executor.execute(::drainCheckpoints)
        } catch (_: Exception) {
            synchronized(lock) {
                workerQueued = false
                mutableStatus.value = SaveStatus.FAILED
            }
        }
    }

    private fun drainCheckpoints() {
        while (true) {
            val startedGeneration = synchronized(lock) { generation }
            // Never hold our state lock or the external preferences monitor during disk I/O.
            val succeeded = try {
                commitAction()
            } catch (_: Exception) {
                false
            }
            val finished = synchronized(lock) {
                if (startedGeneration != generation) false else {
                    workerQueued = false
                    mutableStatus.value = if (succeeded) SaveStatus.SAVED else SaveStatus.FAILED
                    true
                }
            }
            if (finished) return
        }
    }

    companion object {
        private val writers = IdentityHashMap<SharedPreferences, CompletionPersistence>()
        private val diskExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "reaction-rush-save").apply { isDaemon = true }
            }
        }

        /** One writer per process-cached preference file, surviving repository recreation. */
        @SuppressLint("UseKtx") // KTX edit(commit = true) discards the Boolean save acknowledgment.
        fun shared(prefs: SharedPreferences): CompletionPersistence = synchronized(writers) {
            writers.getOrPut(prefs) {
                CompletionPersistence(diskExecutor) { prefs.edit().commit() }
            }
        }
    }
}
