package com.maurointeractive.reactionrush

class ForegroundMonitor {
    private var resumed = false
    private var windowFocused = false
    private val listeners = linkedSetOf<Registration>()
    private val pending = ArrayDeque<Notification>()
    private var notifying = false

    val isForeground: Boolean get() = resumed && windowFocused

    fun setResumed(value: Boolean) {
        val previous = isForeground
        resumed = value
        notifyChange(previous)
    }

    fun setWindowFocused(value: Boolean) {
        val previous = isForeground
        windowFocused = value
        notifyChange(previous)
    }

    fun observe(listener: (Boolean) -> Unit): () -> Unit {
        val registration = Registration(listener)
        listeners += registration
        val unregister: () -> Unit = {
            registration.active = false
            listeners -= registration
        }
        try {
            listener(isForeground)
        } catch (failure: Throwable) {
            unregister()
            throw failure
        }
        return unregister
    }

    private fun notifyChange(previous: Boolean) {
        if (previous == isForeground) return
        // Snapshot at the transition: observers added during delivery get their initial
        // value, but never an already queued transition from before their registration.
        pending.addLast(Notification(isForeground, listeners.toList()))
        if (notifying) return
        notifying = true
        try {
            while (pending.isNotEmpty()) {
                val notification = pending.removeFirst()
                notification.listeners.forEach { registration ->
                    if (registration.active) registration.listener(notification.foreground)
                }
            }
        } finally {
            notifying = false
        }
    }

    private class Registration(val listener: (Boolean) -> Unit) {
        var active = true
    }

    private data class Notification(val foreground: Boolean, val listeners: List<Registration>)
}

/** Identity is the ticket: it cannot be restored or matched by a reused generation number. */
class RoundTicket internal constructor()

class RoundTimingGuard {
    private var foreground = false
    private var currentTicket: RoundTicket? = null
    private var phase: Phase? = null
    private var goTimeMs = 0L

    val isForeground: Boolean get() = foreground

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) cancel()
    }

    fun beginWaiting(): RoundTicket? {
        cancel()
        if (!foreground) return null
        return RoundTicket().also {
            currentTicket = it
            phase = Phase.WAITING
        }
    }

    fun signalGo(ticket: RoundTicket, nowMs: Long): Boolean {
        if (!foreground || ticket !== currentTicket) return false
        if (nowMs < 0L) {
            cancel()
            return false
        }
        if (phase != Phase.WAITING) return false
        goTimeMs = nowMs
        phase = Phase.GO
        return true
    }

    fun react(ticket: RoundTicket, nowMs: Long): Int? {
        if (!foreground || ticket !== currentTicket) return null
        if (nowMs < 0L) {
            cancel()
            return null
        }
        if (phase != Phase.GO) return null
        if (nowMs < goTimeMs) {
            cancel()
            return null
        }
        // Both clocks are nonnegative and ordered, so subtraction cannot overflow.
        val elapsed = nowMs - goTimeMs
        cancel()
        return elapsed.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun cancel() {
        currentTicket = null
        phase = null
        goTimeMs = 0L
    }

    private enum class Phase { WAITING, GO }
}
