package com.cosmerearchives.shortscreator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSearchTest {
    private val transcript = listOf(
        SpokenWord("Hello,", 100, 400),
        SpokenWord("world!", 410, 800),
        SpokenWord("hello", 1400, 1700),
        SpokenWord("world", 1710, 2000)
    )

    @Test fun findsEveryCaseInsensitivePhrase() {
        val matches = SpeechSearch.find(transcript, "HELLO world")
        assertEquals(2, matches.size)
        assertEquals(100L, matches[0].startMs)
        assertEquals(2000L, matches[1].endMs)
    }

    @Test fun handlesNoMatchesAndBlankQueries() {
        assertTrue(SpeechSearch.find(transcript, "missing").isEmpty())
        assertTrue(SpeechSearch.find(transcript, "  ").isEmpty())
    }

    @Test fun contextIsClampedToMediaBoundaries() {
        val selection = SpeechSearch.select(SpeechMatch("hello world", 100, 800), 2000, 2500, 2300)
        assertEquals(0L, selection.startMs)
        assertEquals(2300L, selection.endMs)
    }

    @Test fun supportsExactPhraseWithZeroContext() {
        assertEquals(TrimSelection(410, 800), SpeechSearch.select(SpeechMatch("world", 410, 800), 0, 0, 2000))
    }
}
