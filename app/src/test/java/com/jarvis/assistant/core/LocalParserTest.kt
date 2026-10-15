package com.jarvis.assistant.core

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalParserTest {
    @Test
    fun everyPhraseIsUnderstood() {
        val fails = ParserCases.run()
        assertTrue("Parser failures:\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
