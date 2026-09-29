package dev.periy.bridge.music

import android.content.Context
import dev.periy.bridge.server.EventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The songs with a heart, as Namida's player keeps them: tapped on the phone's player or the
 * page's, kept on the phone, and the same everywhere (the pages hear of a change at once).
 */
class Favourites(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("favourites", Context.MODE_PRIVATE)

    /** Newest heart first. Kept beside the set; a phone from before the order was kept starts from the set. */
    private val _order = MutableStateFlow(loadOrder())
    val order: StateFlow<List<Long>> = _order

    private val _ids = MutableStateFlow(_order.value.toSet())
    val ids: StateFlow<Set<Long>> = _ids

    fun has(id: Long) = id in _ids.value

    fun set(id: Long, on: Boolean) {
        if (on == has(id)) return
        val order = if (on) listOf(id) + _order.value else _order.value - id
        _order.value = order
        _ids.value = order.toSet()
        prefs.edit()
            .putStringSet(K_IDS, order.map { it.toString() }.toSet())
            .putString(K_ORDER, order.joinToString(","))
            .apply()
        EventBus.emit("favourites", json())
    }

    fun toggle(id: Long) = set(id, !has(id))

    /** As the pages take it: a list of song ids, newest heart first. */
    fun json(): String = _order.value.joinToString(",", "[", "]")

    private fun loadOrder(): List<Long> {
        val set = prefs.getStringSet(K_IDS, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        val kept = prefs.getString(K_ORDER, null)?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty().filter { it in set }
        return (kept + (set - kept.toSet())).distinct()
    }

    private companion object {
        const val K_IDS = "ids"
        const val K_ORDER = "order"
    }
}
