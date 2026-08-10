package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBuildOutputTest {
    @Test
    fun shortOutputIsUnchanged() {
        assertEquals("hello\n", boundedWorkspaceOutput("hello\n"))
    }

    @Test
    fun longOutputRetainsOnlyABoundedTail() {
        val tail = "important final output"
        val bounded = boundedWorkspaceOutput("x".repeat(MAX_WORKSPACE_OUTPUT_CHARS) + tail)

        assertEquals(MAX_WORKSPACE_OUTPUT_CHARS, bounded.length)
        assertTrue(bounded.startsWith("… earlier output was trimmed"))
        assertTrue(bounded.endsWith(tail))
    }

    @Test
    fun repeatedAppendingNeverExceedsTheLimit() {
        var output = ""
        repeat(400) { output = appendWorkspaceOutput(output, "z".repeat(1024)) }

        assertEquals(MAX_WORKSPACE_OUTPUT_CHARS, output.length)
    }

    @Test
    fun terminalOutputRemovesAnsiColorAndTitleSequences() {
        val colored = "\u001B[38;5;246mModule not found\u001B[0m\n" +
            "\u001B]0;vite server\u0007ready\n"

        assertEquals("Module not found\nready\n", stripTerminalControlSequences(colored))
        assertEquals("before Module not found\n", appendWorkspaceOutput("before ", colored.substringBefore("\u001B]")))
    }

    @Test
    fun carriageReturnUpdatesOneProgressLineInPlace() {
        var output = appendWorkspaceOutput("", "Downloading [##--------] 20%")
        output = appendWorkspaceOutput(output, "\rDownloading [######----] 60%")

        assertEquals("Downloading [######----] 60%", output)
    }

    @Test
    fun eraseLineRemovesTheOldProgressTail() {
        val output = appendWorkspaceOutput("", "Installing a very long package\r\u001B[2KDone\n")

        assertEquals("Done\n", output)
    }

    @Test
    fun terminalProgressRecognizesPercentFractionAndIndeterminateOperations() {
        assertEquals(
            0.64f,
            terminalProgressState("Downloading archive 64%", running = true)?.fraction,
        )
        assertEquals(
            0.4f,
            terminalProgressState("[2/5] Building target", running = true)?.fraction,
        )
        assertEquals(
            null,
            terminalProgressState("Installing web dependencies…", running = true)?.fraction,
        )
        assertEquals(null, terminalProgressState("Downloading 80%", running = false))
    }

    @Test
    fun terminalPasteNormalizesLineEndingsAndDropsOnlyTrailingNewlines() {
        assertEquals(
            "printf one\nprintf two",
            normalizeTerminalPasteText("printf one\r\nprintf two\r\n"),
        )
        assertEquals("echo hello", normalizeTerminalPasteText("echo hello\n\n"))
    }
}
