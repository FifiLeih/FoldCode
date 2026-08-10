package dev.foldcode.ide

internal const val MAX_WORKSPACE_OUTPUT_CHARS = 256 * 1024
internal const val MAX_IMPORTED_EDITOR_FILE_BYTES = 5_000_000
private const val EARLIER_OUTPUT_MARKER = "… earlier output was trimmed from the in-memory console …\n"

/** Keep interactive output bounded; build engines retain complete logs on disk where applicable. */
internal fun boundedWorkspaceOutput(value: String): String {
    if (value.length <= MAX_WORKSPACE_OUTPUT_CHARS) return value
    return EARLIER_OUTPUT_MARKER + value.takeLast(MAX_WORKSPACE_OUTPUT_CHARS - EARLIER_OUTPUT_MARKER.length)
}

internal fun appendWorkspaceOutput(current: String, appended: String): String =
    boundedWorkspaceOutput(renderTerminalOutput(current, appended))

/**
 * Applies the small terminal-control subset used by progress meters.
 *
 * FoldCode deliberately keeps a selectable Compose transcript instead of a
 * bitmap terminal emulator. Interpreting carriage return, backspace and ANSI
 * erase-line/cursor-horizontal commands is enough for curl, package managers,
 * compilers and user programs to update one progress line in place.
 */
internal fun renderTerminalOutput(current: String, appended: String): String {
    val output = StringBuilder(current)
    var cursor = output.length
    var index = 0

    fun lineStart(): Int {
        val newline = if (cursor <= 0) -1 else output.lastIndexOf("\n", cursor - 1)
        return if (newline < 0) 0 else newline + 1
    }

    fun lineEnd(): Int {
        val newline = output.indexOf("\n", cursor.coerceAtMost(output.length))
        return if (newline < 0) output.length else newline
    }

    fun moveToColumn(column: Int) {
        val start = lineStart()
        val end = lineEnd()
        cursor = (start + column.coerceAtLeast(0)).coerceAtMost(end)
    }

    fun eraseLine(mode: Int) {
        val start = lineStart()
        val end = lineEnd()
        when (mode) {
            1 -> {
                output.delete(start, (cursor + 1).coerceAtMost(end))
                cursor = start
            }
            2 -> {
                output.delete(start, end)
                cursor = start
            }
            else -> output.delete(cursor.coerceAtMost(end), end)
        }
    }

    fun applyCsi(parameters: String, command: Char) {
        val values = parameters
            .trimStart('?')
            .split(';')
            .map { it.toIntOrNull() ?: 0 }
        val amount = (values.firstOrNull() ?: 0).let { if (it == 0) 1 else it }
        when (command) {
            'K' -> eraseLine(values.firstOrNull() ?: 0)
            'G' -> moveToColumn(amount - 1)
            'C' -> moveToColumn(cursor - lineStart() + amount)
            'D' -> moveToColumn(cursor - lineStart() - amount)
            // Styling and unsupported screen-wide controls are intentionally
            // consumed rather than printed into the selectable transcript.
        }
    }

    while (index < appended.length) {
        val char = appended[index]
        if (char == '\u001B') {
            when (appended.getOrNull(index + 1)) {
                '[' -> {
                    var end = index + 2
                    while (end < appended.length && appended[end].code !in 0x40..0x7e) end++
                    if (end >= appended.length) break
                    applyCsi(appended.substring(index + 2, end), appended[end])
                    index = end + 1
                    continue
                }
                ']' -> {
                    var end = index + 2
                    while (end < appended.length) {
                        if (appended[end] == '\u0007') {
                            end++
                            break
                        }
                        if (appended[end] == '\u001B' && appended.getOrNull(end + 1) == '\\') {
                            end += 2
                            break
                        }
                        end++
                    }
                    index = end
                    continue
                }
                else -> {
                    index += 2
                    continue
                }
            }
        }

        when (char) {
            '\r' -> cursor = lineStart()
            '\n' -> {
                cursor = lineEnd()
                if (cursor < output.length && output[cursor] == '\n') cursor++
                else {
                    output.insert(cursor, '\n')
                    cursor++
                }
            }
            '\b' -> cursor = (cursor - 1).coerceAtLeast(lineStart())
            '\t' -> {
                val spaces = 8 - (cursor - lineStart()) % 8
                repeat(spaces) {
                    if (cursor < output.length && output[cursor] != '\n') output.setCharAt(cursor, ' ')
                    else output.insert(cursor, ' ')
                    cursor++
                }
            }
            else -> if (char >= ' ' || char == '\u00a0') {
                if (cursor < output.length && output[cursor] != '\n') output.setCharAt(cursor, char)
                else output.insert(cursor, char)
                cursor++
            }
        }
        index++
    }
    return output.toString()
}

internal fun stripTerminalControlSequences(value: String): String = value
    .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
    .replace(Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)"), "")

internal data class TerminalProgressState(
    val label: String,
    val fraction: Float?,
)

/** Detects common package-manager, downloader and build progress formats. */
internal fun terminalProgressState(output: String, running: Boolean): TerminalProgressState? {
    if (!running) return null
    val line = output.lineSequence().map(String::trim).filter(String::isNotEmpty).lastOrNull() ?: return null
    val percent = Regex("(?<!\\d)(\\d{1,3}(?:\\.\\d+)?)\\s*%").find(line)
        ?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 100f)
    val bracketFraction = Regex("\\[(\\d+)\\s*/\\s*(\\d+)]").find(line)
    val contextualFraction = Regex("(?i)(?:downloading|installing|extracting|fetching|linking|building|flashing)[^\\n]*?(\\d+)\\s*/\\s*(\\d+)")
        .find(line)
    val fractionMatch = bracketFraction ?: contextualFraction
    val fraction = percent?.div(100f) ?: fractionMatch?.let { match ->
        val completed = match.groupValues[1].toLongOrNull() ?: return@let null
        val total = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return@let null
        (completed.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    val operationWords = Regex(
        "(?i)\\b(download(?:ing|ed)?|install(?:ing|ed)?|extract(?:ing|ed)?|fetch(?:ing|ed)?|" +
            "resolv(?:ing|ed)?|link(?:ing|ed)?|build(?:ing|ed)?|compil(?:ing|ed)?|flash(?:ing|ed)?)\\b",
    )
    if (fraction == null && !operationWords.containsMatchIn(line)) return null
    return TerminalProgressState(line.take(180), fraction)
}

internal fun visiblePicoBuildProgress(line: String): String? {
    val trimmed = line.trim()
    return when {
        Regex("^\\[\\d+/\\d+\\]\\s+").containsMatchIn(trimmed) -> trimmed
        trimmed == "Configuring Full CMake project…" -> trimmed
        trimmed == "Refreshing incremental CMake project…" -> trimmed
        trimmed == "Starting Ninja build…" -> trimmed
        trimmed.startsWith("-- Configuring done") -> trimmed
        trimmed.startsWith("-- Generating done") -> trimmed
        else -> null
    }
}

internal fun terminalBuildSummary(
    picoProject: Boolean,
    result: BuildResult,
    fullOutput: String,
    prompt: String,
): String {
    if (!result.succeeded) {
        val firstError = fullOutput.lineSequence()
            .map(String::trim)
            .firstOrNull { it.contains("error:", ignoreCase = true) || it.startsWith("Build failed") }
        return buildString {
            appendLine("Build failed")
            if (!firstError.isNullOrBlank()) appendLine(firstError.take(180))
            append("Open OUTPUT for the complete build log")
        }
    }
    if (picoProject) {
        val artifacts = fullOutput.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("UF2:", ignoreCase = true) }
            .distinct()
            .toList()
        return listOf("Build succeeded")
            .plus(artifacts)
            .joinToString("\n")
    }
    val marker = "$ ./program"
    val runtime = fullOutput.substringAfter(marker, "").trim()
    return if (runtime.isBlank()) "Build succeeded" else "$prompt $marker\n$runtime"
}

internal fun BuildResult.wasCancelledByUser(): Boolean = !succeeded && output.lineSequence().any {
    val line = it.trim()
    line == "Run cancelled" || line == "Build cancelled" || line == "Process interrupted"
}
