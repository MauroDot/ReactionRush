package com.maurointeractive.reactionrush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTimingTest {
    @Test
    fun foregroundRequiresBothResumeAndWindowFocusInEitherOrder() {
        val resumeFirst = ForegroundMonitor()
        assertFalse(resumeFirst.isForeground)
        resumeFirst.setResumed(true)
        assertFalse(resumeFirst.isForeground)
        resumeFirst.setWindowFocused(true)
        assertTrue(resumeFirst.isForeground)

        val focusFirst = ForegroundMonitor()
        focusFirst.setWindowFocused(true)
        assertFalse(focusFirst.isForeground)
        focusFirst.setResumed(true)
        assertTrue(focusFirst.isForeground)
        focusFirst.setResumed(false)
        assertFalse(focusFirst.isForeground)
        focusFirst.setWindowFocused(false)
        focusFirst.setResumed(true)
        assertFalse(focusFirst.isForeground)
    }

    @Test
    fun observersImmediatelyReceiveCurrentStateAndOnlyChangedAggregates() {
        val monitor = ForegroundMonitor()
        val events = mutableListOf<Boolean>()
        monitor.observe { events += it }
        assertEquals(listOf(false), events)
        monitor.setResumed(true)
        monitor.setResumed(true)
        assertEquals(listOf(false), events)
        monitor.setWindowFocused(true)
        monitor.setWindowFocused(true)
        assertEquals(listOf(false, true), events)
        monitor.setWindowFocused(false)
        monitor.setResumed(false)
        monitor.setWindowFocused(true)
        assertEquals(listOf(false, true, false), events)
        monitor.setResumed(true)
        assertEquals(listOf(false, true, false, true), events)

        val lateEvents = mutableListOf<Boolean>()
        monitor.observe { lateEvents += it }
        assertEquals(listOf(true), lateEvents)
    }

    @Test
    fun unregisterIsIdempotentAndStopsFurtherNotifications() {
        val monitor = ForegroundMonitor()
        val events = mutableListOf<Boolean>()
        val unregister = monitor.observe { events += it }
        unregister()
        unregister()
        monitor.setResumed(true)
        monitor.setWindowFocused(true)
        monitor.setResumed(false)
        assertEquals(listOf(false), events)
    }

    @Test
    fun reentrantChangesAreDeliveredInTransitionOrderWithoutRecursiveDelivery() {
        val monitor = ForegroundMonitor()
        val firstEvents = mutableListOf<Boolean>()
        val secondEvents = mutableListOf<Boolean>()
        var callbackDepth = 0
        var maximumDepth = 0
        monitor.observe { foreground ->
            callbackDepth++
            maximumDepth = maxOf(maximumDepth, callbackDepth)
            firstEvents += foreground
            if (foreground) monitor.setWindowFocused(false)
            callbackDepth--
        }
        monitor.observe { secondEvents += it }
        monitor.setResumed(true)
        monitor.setWindowFocused(true)
        assertFalse(monitor.isForeground)
        assertEquals(listOf(false, true, false), firstEvents)
        assertEquals(listOf(false, true, false), secondEvents)
        assertEquals(1, maximumDepth)
    }

    @Test
    fun observersAddedDuringDeliveryDoNotReceiveEarlierQueuedTransitions() {
        val monitor = ForegroundMonitor()
        val lateEvents = mutableListOf<Boolean>()
        var added = false
        monitor.observe { foreground ->
            if (foreground && !added) {
                added = true
                monitor.setWindowFocused(false)
                monitor.observe { lateEvents += it }
            }
        }
        monitor.setResumed(true)
        monitor.setWindowFocused(true)
        assertEquals(listOf(false), lateEvents)
        monitor.setWindowFocused(true)
        assertEquals(listOf(false, true), lateEvents)
    }

    @Test
    fun observersRemovedDuringDeliveryAreNotCalledFromItsSnapshot() {
        val monitor = ForegroundMonitor()
        val secondEvents = mutableListOf<Boolean>()
        var unregisterSecond: () -> Unit = {}
        monitor.observe { if (it) unregisterSecond() }
        unregisterSecond = monitor.observe { secondEvents += it }
        monitor.setResumed(true)
        monitor.setWindowFocused(true)
        assertEquals(listOf(false), secondEvents)
    }

    @Test
    fun aFailedInitialObserverIsRemovedBeforeTheExceptionEscapes() {
        val monitor = ForegroundMonitor()
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            monitor.observe {
                calls++
                throw IllegalStateException("initial observation failed")
            }
        }
        monitor.setResumed(true)
        monitor.setWindowFocused(true)
        assertEquals(1, calls)
    }

    @Test
    fun backgroundCannotStartOrSignalARoundAndForegroundReturnDoesNotRestartIt() {
        val guard = RoundTimingGuard()
        assertFalse(guard.isForeground)
        assertNull(guard.beginWaiting())
        guard.setForeground(true)
        val ticket = requireNotNull(guard.beginWaiting())
        guard.setForeground(false)
        assertFalse(guard.isForeground)
        assertFalse(guard.signalGo(ticket, 1_000))
        assertNull(guard.react(ticket, 1_200))
        assertNull(guard.beginWaiting())
        guard.setForeground(true)
        assertFalse(guard.signalGo(ticket, 10_000))
        assertNull(guard.react(ticket, 10_200))
    }

    @Test
    fun losingForegroundDuringGoPreventsHiddenTimeFromBecomingAScore() {
        val guard = foregroundGuard()
        val ticket = requireNotNull(guard.beginWaiting())
        assertTrue(guard.signalGo(ticket, 1_000))
        guard.setForeground(false)
        assertNull(guard.react(ticket, 100_000))
        guard.setForeground(true)
        assertNull(guard.react(ticket, 100_200))
        assertFalse(guard.signalGo(ticket, 100_000))
    }

    @Test
    fun aLongDelayAfterCancellationCannotSignalGo() {
        val guard = foregroundGuard()
        val ticket = requireNotNull(guard.beginWaiting())
        guard.cancel()
        assertFalse(guard.signalGo(ticket, Long.MAX_VALUE))
        assertNull(guard.react(ticket, Long.MAX_VALUE))
    }

    @Test
    fun aSuccessfulRoundSignalsGoOnceAndConsumesExactlyOneReaction() {
        val guard = foregroundGuard()
        val ticket = requireNotNull(guard.beginWaiting())
        assertNull(guard.react(ticket, 1_000))
        assertTrue(guard.signalGo(ticket, 1_000))
        assertFalse(guard.signalGo(ticket, 1_100))
        assertEquals(250, guard.react(ticket, 1_250))
        assertNull(guard.react(ticket, 1_251))
        assertFalse(guard.signalGo(ticket, 1_300))
        assertTrue(guard.isForeground)
    }

    @Test
    fun beginningAnotherRoundInvalidatesWaitingAndGoTicketsByIdentity() {
        val guard = foregroundGuard()
        val first = requireNotNull(guard.beginWaiting())
        val second = requireNotNull(guard.beginWaiting())
        assertNotSame(first, second)
        assertFalse(guard.signalGo(first, 1_000))
        assertTrue(guard.signalGo(second, 1_000))
        val third = requireNotNull(guard.beginWaiting())
        assertNotSame(second, third)
        assertNull(guard.react(second, 1_200))
        assertTrue(guard.signalGo(third, 2_000))
        assertEquals(200, guard.react(third, 2_200))
    }

    @Test
    fun aRecreatedControllerCannotUseThePreviousControllersTicket() {
        val oldGuard = foregroundGuard()
        val oldTicket = requireNotNull(oldGuard.beginWaiting())
        assertTrue(oldGuard.signalGo(oldTicket, 1_000))
        val newGuard = foregroundGuard()
        val newTicket = requireNotNull(newGuard.beginWaiting())
        assertFalse(newGuard.signalGo(oldTicket, 2_000))
        assertNull(newGuard.react(oldTicket, 2_200))
        assertTrue(newGuard.signalGo(newTicket, 2_000))
        assertEquals(200, newGuard.react(newTicket, 2_200))
    }

    @Test
    fun repeatedBackgroundAndForegroundCyclesNeverReuseTickets() {
        val guard = foregroundGuard()
        repeat(20) { cycle ->
            val stale = requireNotNull(guard.beginWaiting())
            if (cycle % 2 == 0) assertTrue(guard.signalGo(stale, 1_000))
            guard.setForeground(false)
            guard.setForeground(true)
            assertFalse(guard.signalGo(stale, 2_000))
            assertNull(guard.react(stale, 2_200))
            val fresh = requireNotNull(guard.beginWaiting())
            assertTrue(guard.signalGo(fresh, 3_000))
            assertEquals(100, guard.react(fresh, 3_100))
        }
    }

    @Test
    fun negativeGoClocksCancelTheMatchingTicket() {
        val guard = foregroundGuard()
        listOf(-1L, Long.MIN_VALUE).forEach { clock ->
            val ticket = requireNotNull(guard.beginWaiting())
            assertFalse(guard.signalGo(ticket, clock))
            assertFalse(guard.signalGo(ticket, 1_000))
            assertNull(guard.react(ticket, 1_200))
        }
    }

    @Test
    fun backwardOrNegativeReactionClocksCancelTheMatchingTicket() {
        val guard = foregroundGuard()
        listOf(999L, -1L, Long.MIN_VALUE).forEach { clock ->
            val ticket = requireNotNull(guard.beginWaiting())
            assertTrue(guard.signalGo(ticket, 1_000))
            assertNull(guard.react(ticket, clock))
            assertNull(guard.react(ticket, 1_200))
            assertFalse(guard.signalGo(ticket, 2_000))
        }
    }

    @Test
    fun scoresClampToPositiveMillisecondsAndAvoidLongSubtractionOverflow() {
        val guard = foregroundGuard()
        listOf(
            Triple(0L, 0L, 1),
            Triple(0L, 1L, 1),
            Triple(0L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE),
            Triple(0L, Int.MAX_VALUE.toLong() + 1, Int.MAX_VALUE),
            Triple(0L, Long.MAX_VALUE, Int.MAX_VALUE),
            Triple(Long.MAX_VALUE - 5, Long.MAX_VALUE, 5),
            Triple(Long.MAX_VALUE, Long.MAX_VALUE, 1)
        ).forEach { (go, reaction, expected) ->
            val ticket = requireNotNull(guard.beginWaiting())
            assertTrue(guard.signalGo(ticket, go))
            assertEquals(expected, guard.react(ticket, reaction))
        }
    }

    @Test
    fun invalidStaleClocksCannotCancelTheCurrentRound() {
        val guard = foregroundGuard()
        val stale = requireNotNull(guard.beginWaiting())
        val current = requireNotNull(guard.beginWaiting())
        assertFalse(guard.signalGo(stale, Long.MIN_VALUE))
        assertNull(guard.react(stale, Long.MIN_VALUE))
        assertTrue(guard.signalGo(current, 1_000))
        assertNull(guard.react(stale, -1))
        assertEquals(200, guard.react(current, 1_200))
    }

    @Test
    fun repeatedForegroundTrueDoesNotResetAnActiveRoundAndCancelIsIdempotent() {
        val guard = foregroundGuard()
        val ticket = requireNotNull(guard.beginWaiting())
        assertTrue(guard.signalGo(ticket, 1_000))
        guard.setForeground(true)
        assertEquals(200, guard.react(ticket, 1_200))
        guard.cancel()
        guard.cancel()
        assertTrue(guard.isForeground)
        val next = requireNotNull(guard.beginWaiting())
        assertTrue(guard.signalGo(next, 2_000))
    }

    @Test
    fun monitorFocusLossSynchronouslyInvalidatesAnActiveTimingTicket() {
        val monitor = ForegroundMonitor()
        val guard = RoundTimingGuard()
        monitor.observe(guard::setForeground)
        monitor.setResumed(true)
        monitor.setWindowFocused(true)
        val ticket = requireNotNull(guard.beginWaiting())
        assertTrue(guard.signalGo(ticket, 1_000))
        monitor.setWindowFocused(false)
        assertFalse(guard.isForeground)
        assertNull(guard.react(ticket, 1_200))
        monitor.setWindowFocused(true)
        assertTrue(guard.isForeground)
        assertNull(guard.react(ticket, 1_300))
    }

    private fun foregroundGuard(): RoundTimingGuard = RoundTimingGuard().apply { setForeground(true) }
}
