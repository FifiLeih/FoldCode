package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWebSessionUiTest {
    @Test
    fun `ordinary terminal commands do not turn Run into Stop`() {
        val shellCommand = TerminalSessionInfo(id = 2, commandRunning = true)

        assertFalse(workspaceToolbarOperationRunning(false, listOf(shellCommand)))
        assertTrue(workspaceToolbarOperationRunning(true, listOf(shellCommand)))
    }

    @Test
    fun `owned web server keeps Stop visible independently of selected terminal`() {
        val webServer = TerminalSessionInfo(id = 1, commandRunning = true, webServerOwner = true)
        val selectedShell = TerminalSessionInfo(id = 2, commandRunning = false)

        assertTrue(workspaceToolbarOperationRunning(false, listOf(webServer, selectedShell)))
    }

    @Test
    fun `disconnecting local preview unloads Vite client`() {
        val local = ProjectFile(
            name = WEB_PREVIEW_TAB,
            content = "http://127.0.0.1:3000",
            readOnly = true,
            kind = ProjectFileKind.WebPreview,
        )

        val disconnected = disconnectedWebPreviewFiles(mapOf(WEB_PREVIEW_TAB to local))

        assertEquals(DEFAULT_WEB_BROWSER_URL, disconnected.getValue(WEB_PREVIEW_TAB).content)
    }

    @Test
    fun `disconnect leaves a normal browser address unchanged`() {
        val browser = ProjectFile(
            name = WEB_PREVIEW_TAB,
            content = "https://example.com",
            readOnly = true,
            kind = ProjectFileKind.WebPreview,
        )
        val files = mapOf(WEB_PREVIEW_TAB to browser)

        assertSame(files, disconnectedWebPreviewFiles(files))
    }

    @Test
    fun `failed web server receives a visible exit notice`() {
        assertEquals(
            "Web server exited with status 1. Check the output above; port 3000 may already be in use.",
            terminalProcessExitNotice(status = 1, webServerOwner = true),
        )
        assertEquals("Command exited with status 2.", terminalProcessExitNotice(status = 2, webServerOwner = false))
        assertEquals(null, terminalProcessExitNotice(status = 0, webServerOwner = false))
    }
}
