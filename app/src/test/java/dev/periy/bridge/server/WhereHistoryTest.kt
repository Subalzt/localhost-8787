package dev.periy.bridge.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

class WhereHistoryTest {
    private lateinit var dir: File
    private lateinit var h: WhereHistory

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("where-history").toFile()
        h = WhereHistory(dir)
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    /** A moment on [day] (days back from today) at [hour]:[minute], whatever the time zone. */
    private fun at(daysBack: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.now().minusDays(daysBack).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** A position [north] metres north of a fixed place. */
    private fun fix(t: Long, north: Double = 0.0, east: Double = 0.0) =
        Fix(25.0 + north / 111_195.0, 81.0 + east / (111_195.0 * Math.cos(Math.toRadians(25.0))), 10f, t)

    @Test
    fun eachDayHasItsOwnSummary() {
        h.record("self", fix(at(1, 9), 0.0))
        h.record("self", fix(at(1, 10), 1000.0))
        h.record("self", fix(at(0, 8), 0.0))
        h.record("self", fix(at(0, 8, 30), 500.0))
        h.record("self", fix(at(0, 9), 1500.0))
        val days = h.days("self")
        assertEquals(2, days.size)
        assertEquals(LocalDate.now().minusDays(1).toString(), days[0].day)
        assertEquals(2, days[0].points)
        assertEquals(1000.0, days[0].metres, 15.0)
        assertEquals(3, days[1].points)
        assertEquals(1500.0, days[1].metres, 20.0)
        assertTrue(days[1].lastAt > days[1].firstAt)
        // south, west, north, east
        assertTrue(days[1].box[2] > days[1].box[0])
    }

    @Test
    fun aStillPhonesWobbleIsNotDistance() {
        // Five minutes apart, a few metres each way: it did not go anywhere.
        val p = (0 until 20).map { fix(at(0, 8) + it * 300_000L, north = if (it % 2 == 0) 0.0 else 6.0) }
        assertEquals(0.0, WhereHistory.metresOf(p), 0.001)
    }

    @Test
    fun aJumpIsNotTravel() {
        // 500 km in a minute is a bad position, not a journey.
        val p = listOf(fix(at(0, 8)), fix(at(0, 8) + 60_000L, north = 500_000.0), fix(at(0, 8) + 120_000L, north = 500_100.0))
        assertEquals(100.0, WhereHistory.metresOf(p), 5.0)
    }

    @Test
    fun oneDayAndTheOverallTrailComeBackInOrder() {
        // Written out of order and twice: it is read back once each, oldest first.
        val a = fix(at(0, 9), 100.0)
        val b = fix(at(0, 8), 0.0)
        h.record("self", a); h.record("self", b); h.record("self", a)
        val today = LocalDate.now().toString()
        val pts = h.day("self", today)
        assertEquals(listOf(b.at, a.at), pts.map { it.at })
        assertEquals(2, h.all("self").size)
        assertEquals(emptyList<Fix>(), h.day("self", "../../where"))
    }

    @Test
    fun theOverallTrailIsThinnedButKeepsItsEnds() {
        for (d in 0L..2L) for (i in 0 until 100) h.record("self", fix(at(d, 8) + i * 60_000L, north = i * 20.0))
        val all = h.all("self", max = 50)
        assertTrue(all.size in 50..51)
        assertEquals(h.all("self", max = 10_000).first().at, all.first().at)
        assertEquals(h.all("self", max = 10_000).last().at, all.last().at)
    }

    @Test
    fun seedingOnlyFillsAnEmptyHistory() {
        val trail = listOf(fix(at(0, 8)), fix(at(0, 9), 50.0))
        h.seed("self", trail)
        assertEquals(2, h.days("self").single().points)
        h.seed("self", trail + fix(at(0, 10), 99.0))
        assertEquals(2, h.days("self").single().points)
    }

    @Test
    fun oldDaysAreRemovedAndSoIsAPlace() {
        h.record("self", fix(at(0, 8)))
        h.record("self", fix(at(100, 8)))
        h.record("laptop-1", fix(at(0, 8)))
        h.prune(90)
        assertEquals(1, h.days("self").size)
        h.clear("laptop-1")
        assertEquals(0, h.days("laptop-1").size)
        assertEquals(1, h.days("self").size)
    }

    @Test
    fun theBodyIsJsonForTheDaysAndTheTrack() {
        h.record("self", fix(at(0, 8)))
        h.record("self", fix(at(0, 9), 200.0))
        assertTrue(h.body("self", null).contains("\"days\""))
        assertTrue(h.body("self", LocalDate.now().toString()).contains("\"points\""))
        assertTrue(h.body("self", "all").contains("\"day\":\"all\""))
    }
}
