package com.maurointeractive.reactionrush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CompletionPersistenceTest {
    @Test
    fun checkpointQueuesTheBarrierWithoutCommittingOnTheCaller() {
        val queue = QueuedExecutor()
        var commits = 0
        val persistence = CompletionPersistence(queue) { commits++; true }
        assertEquals(SaveStatus.IDLE, persistence.status.value)
        persistence.retry()
        assertEquals(0, queue.size)

        persistence.checkpoint()

        assertEquals(SaveStatus.SAVING, persistence.status.value)
        assertEquals(0, commits)
        assertEquals(1, queue.size)
        queue.runNext()
        assertEquals(1, commits)
        assertEquals(SaveStatus.SAVED, persistence.status.value)
    }

    @Test
    fun blockedDiskWorkRunsOnTheExecutorAndDoesNotBlockCheckpoint() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        val persistence = CompletionPersistence(executor) {
            worker.set(Thread.currentThread())
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            persistence.checkpoint()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNotEquals(caller, worker.get())
            assertEquals(SaveStatus.SAVING, persistence.status.value)
            release.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(SaveStatus.SAVED, persistence.status.value)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun queuedRequestsCoalesceIntoOneBarrierForTheLatestGeneration() {
        val queue = QueuedExecutor()
        var commits = 0
        val persistence = CompletionPersistence(queue) { commits++; true }
        repeat(10) { persistence.checkpoint() }
        assertEquals(1, queue.size)
        queue.runNext()
        assertEquals(1, commits)
        assertEquals(SaveStatus.SAVED, persistence.status.value)
    }

    @Test
    fun olderFailureCannotReplaceANewerPendingStatus() {
        val queue = QueuedExecutor()
        var commits = 0
        lateinit var persistence: CompletionPersistence
        persistence = CompletionPersistence(queue) {
            commits++
            if (commits == 1) {
                persistence.checkpoint()
                false
            } else {
                assertEquals(SaveStatus.SAVING, persistence.status.value)
                true
            }
        }
        persistence.checkpoint()
        queue.runNext()
        assertEquals(2, commits)
        assertEquals(SaveStatus.SAVED, persistence.status.value)
        assertEquals(0, queue.size)
    }

    @Test
    fun olderSuccessCannotHideFailureOfANewerGeneration() {
        val queue = QueuedExecutor()
        var commits = 0
        lateinit var persistence: CompletionPersistence
        persistence = CompletionPersistence(queue) {
            commits++
            if (commits == 1) {
                persistence.checkpoint()
                true
            } else {
                assertEquals(SaveStatus.SAVING, persistence.status.value)
                false
            }
        }
        persistence.checkpoint()
        queue.runNext()
        assertEquals(2, commits)
        assertEquals(SaveStatus.FAILED, persistence.status.value)
    }

    @Test
    fun failedBarrierCanRetryWithoutAnyGameplayCallback() {
        val queue = QueuedExecutor()
        var commits = 0
        val persistence = CompletionPersistence(queue) { ++commits > 1 }
        persistence.checkpoint()
        queue.runNext()
        assertEquals(SaveStatus.FAILED, persistence.status.value)

        persistence.retry()
        assertEquals(SaveStatus.SAVING, persistence.status.value)
        assertEquals(1, commits)
        queue.runNext()
        assertEquals(2, commits)
        assertEquals(SaveStatus.SAVED, persistence.status.value)
    }

    @Test
    fun commitExceptionsBecomeFailureAndAllowRetry() {
        val queue = QueuedExecutor()
        var commits = 0
        val persistence = CompletionPersistence(queue) {
            if (++commits == 1) throw IllegalStateException("Storage unavailable")
            true
        }
        persistence.checkpoint()
        queue.runNext()
        assertEquals(SaveStatus.FAILED, persistence.status.value)
        persistence.retry()
        queue.runNext()
        assertEquals(SaveStatus.SAVED, persistence.status.value)
        assertEquals(2, commits)
    }

    @Test
    fun executorRejectionBecomesFailureAndCanRetryWhenSchedulingRecovers() {
        val queue = QueuedExecutor()
        var reject = true
        var commits = 0
        val persistence = CompletionPersistence(Executor { task ->
            if (reject) throw RejectedExecutionException("Executor unavailable")
            queue.execute(task)
        }) { commits++; true }
        persistence.checkpoint()
        assertEquals(SaveStatus.FAILED, persistence.status.value)
        assertEquals(0, commits)
        reject = false
        persistence.retry()
        assertEquals(SaveStatus.SAVING, persistence.status.value)
        queue.runNext()
        assertEquals(1, commits)
        assertEquals(SaveStatus.SAVED, persistence.status.value)
    }

    @Test
    fun retriesWhileIdleSavingOrSavedDoNotQueueRedundantBarriers() {
        val queue = QueuedExecutor()
        var commits = 0
        val persistence = CompletionPersistence(queue) { commits++; true }
        persistence.retry()
        assertEquals(0, queue.size)
        persistence.checkpoint()
        persistence.retry()
        assertEquals(1, queue.size)
        queue.runNext()
        persistence.retry()
        assertEquals(0, queue.size)
        assertEquals(1, commits)
        persistence.checkpoint()
        queue.runNext()
        assertEquals(2, commits)
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
    }
}
