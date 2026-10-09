package dev.periy.bridge.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the timeline says of one day: how many positions, how far, from when to when, and the box round it. */
@Serializable
data class DaySummary(
    /** yyyy-MM-dd, by this phone's own clock and time zone. */
    val day: String,
    val points: Int,
    val metres: Double,
    val firstAt: Long,
    val lastAt: Long,
    /** south, west, north, east. */
    val box: List<Double>,
)

/** Every day with a trail for a phone or laptop, oldest first. */
@Serializable
data class DayList(val id: String, val days: List<DaySummary>)

/** One day's positions (or every day's, thinned, for the overall trail), oldest first. */
@Serializable
data class DayTrack(val id: String, val day: String, val points: List<Fix>)

/**
 * The positions behind the map's timeline, kept on this phone only: a small file for each day of
 * each phone and laptop of its own (the same positions the trail has, a new one every 15 m or 5
 * minutes), kept for [KEEP_DAYS] days. This is what lets the map show the overall trail and each
 * day on its own, as a timeline does.
 */
class WhereHistory(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sums = HashMap<String, Triple<Long, Long, DaySummary>>()

    private fun folder(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifEmpty { "_" })
    private fun dayOf(at: Long): String = LocalDate.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).toString()

    private fun daysOf(id: String): List<File> =
        folder(id).listFiles { f -> f.isFile && f.name.matches(FILE) }?.sortedBy { it.name }.orEmpty()

    /** One more position, in its day's file. */
    @Synchronized
    fun record(id: String, fix: Fix) {
        val f = File(folder(id), dayOf(fix.at) + ".jsonl")
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(json.encodeToString(Fix.serializer(), fix) + "\n")
        }
    }

    /** The trail the phone already holds, as the first of the history (only when there is none yet). */
    @Synchronized
    fun seed(id: String, trail: List<Fix>) {
        if (daysOf(id).isNotEmpty()) return
        trail.sortedBy { it.at }.distinctBy { it.at }.forEach { record(id, it) }
    }

    private fun read(f: File): List<Fix> = runCatching {
        f.readLines().mapNotNull { runCatching { json.decodeFromString(Fix.serializer(), it) }.getOrNull() }
    }.getOrDefault(emptyList()).distinctBy { it.at }.sortedBy { it.at }

    /** Every day there is something for, oldest first. */
    @Synchronized
    fun days(id: String): List<DaySummary> = daysOf(id).mapNotNull { f ->
        val stamp = Triple(f.length(), f.lastModified(), null as DaySummary?)
        val cached = sums[f.path]
        if (cached != null && cached.first == stamp.first && cached.second == stamp.second) return@mapNotNull cached.third
        val p = read(f)
        if (p.isEmpty()) return@mapNotNull null
        val s = DaySummary(
            f.name.removeSuffix(".jsonl"), p.size, metresOf(p), p.first().at, p.last().at,
            listOf(p.minOf { it.lat }, p.minOf { it.lon }, p.maxOf { it.lat }, p.maxOf { it.lon }),
        )
        sums[f.path] = Triple(f.length(), f.lastModified(), s)
        s
    }

    @Synchronized
    fun day(id: String, day: String): List<Fix> =
        if (!day.matches(DAY)) emptyList() else read(File(folder(id), "$day.jsonl"))

    /** Every kept day together, thinned to at most [max] positions (the first and last always kept). */
    @Synchronized
    fun all(id: String, max: Int = 4000): List<Fix> {
        val p = daysOf(id).flatMap { read(it) }
        if (p.size <= max) return p
        val step = p.size.toDouble() / max
        return (0 until max).map { p[(it * step).toInt()] }.let { if (it.last() === p.last()) it else it + p.last() }
    }

    /** All of one place's history, gone (the person asked, or it was taken off the map). */
    @Synchronized
    fun clear(id: String) {
        folder(id).deleteRecursively()
        sums.keys.removeAll { it.startsWith(folder(id).path) }
    }

    /** Days older than [keepDays] go. */
    @Synchronized
    fun prune(keepDays: Int = KEEP_DAYS) {
        val oldest = LocalDate.now().minusDays(keepDays.toLong()).toString()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            d.listFiles()?.filter { it.name.matches(FILE) && it.name.removeSuffix(".jsonl") < oldest }?.forEach { it.delete() }
            if (d.listFiles().isNullOrEmpty()) d.delete()
        }
    }

    /** The JSON for a place: its days (no [day]), one day, or "all" of them. */
    fun body(id: String, day: String?): String = when (day) {
        null, "" -> json.encodeToString(DayList.serializer(), DayList(id, days(id)))
        "all" -> json.encodeToString(DayTrack.serializer(), DayTrack(id, "all", all(id)))
        else -> json.encodeToString(DayTrack.serializer(), DayTrack(id, day, day(id, day)))
    }

    companion object {
        const val KEEP_DAYS = 90
        private val FILE = Regex("""\d{4}-\d{2}-\d{2}\.jsonl""")
        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

        /**
         * How far a trail goes, in metres. A step under 12 m is a still phone's position wobbling, and
         * one faster than 324 km/h is a jump in the positions, not travel: neither counts.
         */
        fun metresOf(p: List<Fix>): Double {
            var d = 0.0
            for (i in 1 until p.size) {
                val m = Where.metres(p[i - 1], p[i])
                val s = (p[i].at - p[i - 1].at) / 1000.0
                if (m < 12.0 || (s > 0 && m / s > 90.0)) continue
                d += m
            }
            return d
        }
    }
}
