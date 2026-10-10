package dev.periy.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffMusicTest {
    private fun offer(id: String, at: Long = System.currentTimeMillis()): String {
        val m = MusicHandoff(id, listOf(1L, 2L), 0, 5_000, at, "Phone")
        val f = Handoff::class.java.getDeclaredField("pending").apply { isAccessible = true }
        f.set(Handoff, m)
        val c = Handoff::class.java.getDeclaredField("claimed").apply { isAccessible = true }
        c.set(Handoff, null)
        return id
    }

    @Test
    fun claimingDoesNotStopThePhoneButConfirmingDoes() {
        val id = offer("abc")
        assertNotNull(Handoff.claim(id))
        // Not yet: the page has the music but may be held back by its browser.
        assertTrue(Handoff.confirm(id))
        // Only once.
        assertFalse(Handoff.confirm(id))
    }

    @Test
    fun aPageThatNeverPlaysLeavesThePhonePlaying() {
        val id = offer("slow")
        assertNotNull(Handoff.claim(id))
        // Nobody confirms: nothing says the phone is to stop. Another id never does.
        assertFalse(Handoff.confirm("other"))
        assertTrue(Handoff.confirm(id))
    }

    @Test
    fun onlyOnePageGetsTheMusic() {
        val id = offer("one")
        assertNotNull(Handoff.claim(id))
        assertNull(Handoff.claim(id))
        assertNull(Handoff.claim("latest"))
    }

    @Test
    fun latestTakesWhateverWasOfferedLast() {
        offer("xyz")
        assertEquals("xyz", Handoff.claim("latest")?.id)
    }

    @Test
    fun anOldOfferOrAnOldClaimIsNotTaken() {
        offer("old", at = System.currentTimeMillis() - 200_000)
        assertNull(Handoff.claim("old"))
        val id = offer("fresh")
        assertNotNull(Handoff.claim(id))
        val c = Handoff::class.java.getDeclaredField("claimed").apply { isAccessible = true }
        c.set(Handoff, id to System.currentTimeMillis() - 200_000)
        assertFalse(Handoff.confirm(id))
    }
}
