package dev.foldcode.ide

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpmTerminalEnvironmentTest {
    @Test
    fun npmUsesWritableAppPrivateGlobalPrefixAndCache() {
        val root = Files.createTempDirectory("foldcode-npm-environment").toFile()
        try {
            val files = root.resolve("files")
            val cache = root.resolve("cache")
            val environment = mutableMapOf<String, String>()

            val npm = NpmTerminalEnvironment(files, cache)
            npm.configure(environment)

            assertEquals(files.resolve("npm-global").absolutePath, environment["NPM_CONFIG_PREFIX"])
            assertEquals(cache.resolve("npm-cache").absolutePath, environment["NPM_CONFIG_CACHE"])
            assertTrue(environment.getValue("PATH").startsWith(files.resolve("npm-global/bin").absolutePath))
            assertTrue(npm.globalBin.isDirectory)
            assertTrue(npm.cache.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun globalPackageRepairTargetsFoldCodeNodeLauncher() {
        val root = Files.createTempDirectory("foldcode-npm-launcher").toFile()
        try {
            val npm = NpmTerminalEnvironment(root.resolve("files"), root.resolve("cache"))
            val command = npm.repairGlobalLaunchersCommand()

            assertTrue(command.contains(root.resolve("files/npm-global/lib/node_modules").absolutePath))
            assertTrue(command.contains(root.resolve("files/terminal-bin/node").absolutePath))
            assertTrue(command.contains("#!/usr/bin/env node"))
        } finally {
            root.deleteRecursively()
        }
    }
}
