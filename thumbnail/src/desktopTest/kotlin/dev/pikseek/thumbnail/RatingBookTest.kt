package dev.pikseek.thumbnail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingBookTest {
    private val a = "a".repeat(40)
    private val b = "B".repeat(40)

    @Test
    fun `dislike and undo, keyed by upper-case gcid`() {
        val book = RatingBook().with(a, disliked = true, atMs = 10)
        assertTrue(book.isDisliked(a.uppercase()))
        assertTrue(book.isDisliked(a))
        assertEquals(1, book.dislikedCount)
        val undone = book.with(a, disliked = false, atMs = 20)
        assertFalse(undone.isDisliked(a))
        assertEquals(0, undone.dislikedCount)
        assertFalse(RatingBook().isDisliked(""))
    }

    @Test
    fun `merge keeps the later change of each video`() {
        // 这台：10 时讨厌 a；那台：20 时取消了 a、15 时讨厌 b
        val here = RatingBook().with(a, true, 10)
        val there = RatingBook().with(a, false, 20).with(b, true, 15)
        val merged = here.merge(there)
        assertFalse(merged.isDisliked(a), "那台取消得晚")
        assertTrue(merged.isDisliked(b))
        assertEquals(merged, there.merge(here), "合并不分先后")
    }

    @Test
    fun `old undo records are dropped, dislikes stay`() {
        val day = 24 * 3600 * 1000L
        val book = RatingBook().with(a, false, 0).with(b, true, 0)
        val pruned = book.pruned(nowMs = 200 * day)
        assertEquals(setOf(b.uppercase()), pruned.entries.keys)
        assertEquals(2, book.pruned(nowMs = 10 * day).entries.size)
    }

    @Test
    fun `round trip and refusing newer versions`() {
        val book = RatingBook().with(a, true, 123).with(b, false, 456)
        assertEquals(book, RatingBook.decode(book.encode()))
        assertNull(RatingBook.decode("""{"version":2,"entries":{}}""".encodeToByteArray()))
        assertNull(RatingBook.decode("不是 JSON".encodeToByteArray()))
    }

    @Test
    fun `file name with the drive's copy number is still ours`() {
        assertTrue(RatingBook.isFileName("ratings.psratings"))
        assertTrue(RatingBook.isFileName("ratings(1).psratings"))
        assertTrue(RatingBook.isFileName("ratings (2).psratings"))
        assertFalse(RatingBook.isFileName("ratings.psmarks"))
        assertFalse(RatingBook.isFileName("other.psratings"))
    }
}
