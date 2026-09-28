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

    private val _ids = MutableStateFlow(prefs.getStringSet(K_IDS, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet())
    val ids: StateFlow<Set<Long>> = _ids

    fun has(id: Long) = id in _ids.value

    fun set(id: Long, on: Boolean) {
        val next = if (on) _ids.value + id else _ids.value - id
        if (next == _ids.value) return
        _ids.value = next
        prefs.edit().putStringSet(K_IDS, next.map { it.toString() }.toSet()).apply()
        EventBus.emit("favourites", json())
    }

    fun toggle(id: Long) = set(id, !has(id))

    /** As the pages take it: a list of song ids. */
    fun json(): String = _ids.value.joinToString(",", "[", "]")

    private companion object {
        const val K_IDS = "ids"
    }
}
