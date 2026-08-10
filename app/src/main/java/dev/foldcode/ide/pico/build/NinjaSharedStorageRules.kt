package dev.foldcode.ide

import java.io.File

private const val PERSIST_OUTPUT_MTIME = " && /system/bin/touch -c \"\$out\""
private val compilerRule = Regex("rule (?:ASM|C|CXX)_COMPILER(?:_|$).*")

/**
 * Makes compiler output timestamps durable on Android emulated shared storage.
 *
 * Some compiler output replacement paths leave the FUSE-visible timestamp at
 * its previous value. Ninja records the new timestamp in .ninja_deps, sees the
 * old value in the next process, and recompiles the unchanged object forever.
 * Touching the object inside the compiler rule happens before Ninja records its
 * dependency entry, so the database and persisted filesystem metadata agree.
 */
internal fun stabilizeNinjaCompilerOutputTimestamps(rules: String): String {
    var inCompilerRule = false
    val hasTrailingNewline = rules.endsWith('\n')
    val body = if (hasTrailingNewline) rules.dropLast(1) else rules
    val stabilized = body.split('\n').joinToString("\n") { line ->
        if (line.startsWith("rule ")) inCompilerRule = compilerRule.matches(line)
        if (inCompilerRule && line.startsWith("  command = ") && PERSIST_OUTPUT_MTIME !in line) {
            line + PERSIST_OUTPUT_MTIME
        } else {
            line
        }
    }
    return stabilized + if (hasTrailingNewline) "\n" else ""
}

internal fun installNinjaSharedStorageRules(buildDirectory: File): Boolean {
    val rulesFile = File(buildDirectory, "CMakeFiles/rules.ninja")
    if (!rulesFile.isFile) return false
    val current = rulesFile.readText()
    val stabilized = stabilizeNinjaCompilerOutputTimestamps(current)
    if (stabilized == current) return false
    rulesFile.writeText(stabilized)
    return true
}
