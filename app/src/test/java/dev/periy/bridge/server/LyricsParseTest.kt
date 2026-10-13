package dev.periy.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsParseTest {
    @Test
    fun syllablesAndWhereTheSingingStops() {
        val lines = LyricsText.parseLrc(
            "[00:01.00]<00:01.00>Bee<00:01.20>tho<00:01.40>ven <00:01.80><00:02.50>with<00:03.00>\n[00:04.00]next line\n"
        )
        val w = lines[0].words
        assertEquals(listOf("Bee", "tho", "ven ", "with"), w.map { it.w })
        assertEquals(listOf(1000L, 1200L, 1400L, 2500L), w.map { it.t })
        // A syllable runs to the next; "ven" stops where the mark says, not when "with" starts.
        assertEquals(listOf(1200L, 1400L, 1800L, 3000L), w.map { it.e })
        assertEquals("Beethoven with", lines[0].text)
    }

    @Test
    fun wordsWithoutMarksRunToTheNextAsBefore() {
        val w = LyricsText.parseLrc("[00:01.00]<00:01.00>one <00:01.50>two\n[00:03.00]x\n")[0].words
        assertEquals(listOf(1500L, 3000L), w.map { it.e })
    }
}
