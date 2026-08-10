package dev.foldcode.ide

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedWebServerProcessesTest {
    private val files = File("/data/user/0/dev.foldcode.ide/files")
    private val node = "/data/user/0/dev.foldcode.ide/files/web-runtime/bin/node"

    @Test
    fun `managed server must use packaged node inside private web mirror`() {
        assertTrue(
            isManagedWebServerProcess(
                commandLine = listOf("/system/bin/linker64", node, "node_modules/vite/bin/vite.js"),
                workingDirectory = "/data/user/0/dev.foldcode.ide/files/web-projects/abc123",
                filesDirectory = files,
            ),
        )
    }

    @Test
    fun `language servers and unrelated node processes are preserved`() {
        assertFalse(
            isManagedWebServerProcess(
                commandLine = listOf("/system/bin/linker64", node, "typescript-language-server", "--stdio"),
                workingDirectory = "/storage/emulated/0/FoldCode/Projects/example",
                filesDirectory = files,
            ),
        )
        assertFalse(
            isManagedWebServerProcess(
                commandLine = listOf("/system/bin/linker64", "/data/local/tmp/node", "server.js"),
                workingDirectory = "/data/user/0/dev.foldcode.ide/files/web-projects/abc123",
                filesDirectory = files,
            ),
        )
    }
}
