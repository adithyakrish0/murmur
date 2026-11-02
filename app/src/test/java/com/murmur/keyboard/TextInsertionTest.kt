package com.murmur.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {

    @Test
    fun testTextTrimming() {
        val transcribedText = "  Hello world   "
        val trimmed = transcribedText.trim()
        assertEquals("Hello world", trimmed)
    }

    @Test
    fun testSpacingLogic() {
        val text = "hello"

        val beforeCursor1: CharSequence = " "
        val textToInsert1 = if (beforeCursor1.isNotEmpty() && !beforeCursor1[0].isWhitespace()) {
            " " + text
        } else {
            text
        }
        assertEquals("hello", textToInsert1)

        val beforeCursor2: CharSequence = "d"
        val textToInsert2 = if (beforeCursor2.isNotEmpty() && !beforeCursor2[0].isWhitespace()) {
            " " + text
        } else {
            text
        }
        assertEquals(" hello", textToInsert2)

        val beforeCursor3: CharSequence = ""
        val textToInsert3 = if (beforeCursor3.isNotEmpty() && !beforeCursor3[0].isWhitespace()) {
            " " + text
        } else {
            text
        }
        assertEquals("hello", textToInsert3)
    }
}
