package dev.periy.bridge.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

enum class Direction { INBOUND, OUTBOUND }
enum class TransferState { ACTIVE, STALLED, DONE, FAILED }

data class Transfer(
    val id: String,
    val name: String,
    val direction: Direction,
    val total: Long,
    val transferred: Long,
    /** Smoothed throughput. Surfaced in the UI so thermal throttling looks like a number. */
    val bytesPerSec: Long,
    val state: TransferState,
    val startedAt: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (total > 0) (transferred.toDouble() / total).toFloat() else 0f
}

/**
 * Live view of what is moving, shared by the notification, the app UI and the wake lock.
 *
 * Throughput is an exponential moving average over ~3 seconds rather than an
 * instantaneous rate. A raw rate on a phone jitters violently as the radio and the
 * scheduler do their thing, and a number that flickers between 4 MB/s and 90 MB/s is
 * worse than no number at all -- the whole point is to make a sustained thermal slowdown
 * legible.
 */
object Transfers {

    private val lock = Any()
    private val items = LinkedHashMap<String, Transfer>()
    private val rates = HashMap<String, RateMeter>()

    private val _flow = MutableStateFlow<List<Transfer>>(emptyList())
    val flow: StateFlow<List<Transfer>> = _flow

    /**
     * Non-zero exactly while something is moving. The wake and Wi-Fi locks key off this. A
     * stalled transfer does not count: one never resumed (the page closed) would otherwise
     * hold the Wi-Fi lock for as long as the server runs. A resume makes it active again, and
     * the locks' linger covers a short drop.
     */
    val activeCount: kotlinx.coroutines.flow.Flow<Int> =
        _flow.map { list -> list.count { it.state == TransferState.ACTIVE } }

    fun begin(id: String, name: String, direction: Direction, total: Long, alreadyDone: Long = 0) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            rates[id] = RateMeter(now, alreadyDone)
            items[id] = Transfer(id, name, direction, total, alreadyDone, 0, TransferState.ACTIVE, now, now)
            publish()
        }
    }

    fun progress(id: String, transferred: Long) {
        synchronized(lock) {
            val cur = items[id] ?: return
            val now = System.currentTimeMillis()
            val rate = rates.getOrPut(id) { RateMeter(now, transferred) }.sample(now, transferred)
            items[id] = cur.copy(
                transferred = transferred,
                bytesPerSec = rate,
                state = TransferState.ACTIVE,
                updatedAt = now,
            )
            publish()
        }
    }

    fun finish(id: String, ok: Boolean) {
        synchronized(lock) {
            val cur = items[id] ?: return
            rates.remove(id)
            items[id] = cur.copy(
                state = if (ok) TransferState.DONE else TransferState.FAILED,
                bytesPerSec = 0,
                transferred = if (ok && cur.total > 0) cur.total else cur.transferred,
                updatedAt = System.currentTimeMillis(),
            )
            publish()
        }
    }

    /**
     * An interrupted transfer is not a failure -- it is the normal case this whole design
     * exists for. It stays in the list as STALLED so the resume is visibly a resume.
     */
    fun stall(id: String) {
        synchronized(lock) {
            val cur = items[id] ?: return
            rates.remove(id)
            items[id] = cur.copy(state = TransferState.STALLED, bytesPerSec = 0, updatedAt = System.currentTimeMillis())
            publish()
        }
    }

    fun forget(id: String) = synchronized(lock) {
        items.remove(id); rates.remove(id); publish()
    }

    fun clearFinished() = synchronized(lock) {
        items.entries.removeAll { it.value.state == TransferState.DONE || it.value.state == TransferState.FAILED }
        publish()
    }

    private fun publish() {
        // Trim the tail so a long session cannot grow this without bound.
        while (items.size > MAX_ROWS) {
            val victim = items.entries.firstOrNull { it.value.state != TransferState.ACTIVE } ?: break
            items.remove(victim.key)
        }
        _flow.value = items.values.toList().asReversed()
    }

    private const val MAX_ROWS = 40

    private class RateMeter(startedAt: Long, startBytes: Long) {
        private var lastAt = startedAt
        private var lastBytes = startBytes
        private var ewma = 0.0

        fun sample(now: Long, bytes: Long): Long {
            val dtMs = now - lastAt
            if (dtMs < MIN_WINDOW_MS) return ewma.toLong()
            val instant = (bytes - lastBytes) * 1000.0 / dtMs
            lastAt = now
            lastBytes = bytes
            // alpha derived from the window so the smoothing does not change meaning
            // when samples arrive at a different cadence.
            val alpha = (dtMs / TAU_MS).coerceAtMost(1.0)
            ewma = if (ewma == 0.0) instant else ewma + alpha * (instant - ewma)
            return ewma.toLong()
        }

        companion object {
            const val MIN_WINDOW_MS = 400L
            const val TAU_MS = 3000.0
        }
    }
}
