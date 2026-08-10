package dev.foldcode.ide

import java.io.File

/** Android-safe npm cache, global install prefix, and executable search path. */
internal class NpmTerminalEnvironment(
    filesDirectory: File,
    cacheDirectory: File,
) {
    val globalPrefix = File(filesDirectory, "npm-global")
    val globalBin = File(globalPrefix, "bin")
    val cache = File(cacheDirectory, "npm-cache")
    private val terminalBin = File(filesDirectory, "terminal-bin")
    private val nodeLauncher = File(terminalBin, "node")

    fun configure(environment: MutableMap<String, String>) {
        globalBin.mkdirs()
        cache.mkdirs()
        environment["PATH"] = listOf(
            globalBin.absolutePath,
            terminalBin.absolutePath,
            "/system/bin",
            "/system/xbin",
        ).joinToString(":")
        environment["NPM_CONFIG_PREFIX"] = globalPrefix.absolutePath
        environment["NPM_CONFIG_CACHE"] = cache.absolutePath
        environment["NPM_CONFIG_FOREGROUND_SCRIPTS"] = "true"
    }

    /**
     * npm package launchers normally use /usr/bin/env, which Android does not
     * provide. Make globally installed JavaScript commands use FoldCode's Node
     * launcher while preserving npm's original command status.
     */
    fun repairGlobalLaunchersCommand(): String {
        val modules = shellQuote(File(globalPrefix, "lib/node_modules").absolutePath)
        val replacement = nodeLauncher.absolutePath
            .replace("\\", "\\\\")
            .replace("&", "\\&")
            .replace("|", "\\|")
            .replace("'", "'\\''")
        return "if [ -d $modules ]; then " +
            "find $modules -type f -perm /111 -exec " +
            "sed -i '1s|^#!/usr/bin/env node$|#!$replacement|' {} \\; 2>/dev/null || :; fi"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
