package dev.foldcode.ide

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

internal data class CodeDiagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: String,
    val message: String,
)

internal data class CompletionItem(
    val label: String,
    val insertion: String = label,
    val detail: String = "C/C++ symbol",
    val category: String = "",
)

/** A short, language-neutral label used by the shared completion popup. */
internal fun CompletionItem.displayCategory(): String {
    if (category.isNotBlank()) return category.lowercase().replace('_', ' ')
    val description = detail.lowercase()
    return when {
        "header" in description -> "header"
        "module" in description -> "module"
        "snippet" in description -> "snippet"
        "keyword" in description || "directive" in description -> "keyword"
        "macro" in description -> "macro"
        "method" in description -> "method"
        "function" in description || "procedure" in description ||
            "subroutine" in description || '(' in detail -> "function"
        "class" in description || "struct" in description -> "class"
        "enum" in description -> "enum"
        "interface" in description -> "interface"
        "type" in description -> "type"
        "field" in description || "property" in description -> "field"
        "constant" in description -> "constant"
        "variable" in description || "local" in description || "data item" in description -> "variable"
        "label" in description || "paragraph" in description -> "label"
        "instruction" in description || "register" in description -> "instruction"
        else -> "symbol"
    }
}

/** LSP CompletionItemKind values shared by clangd and rust-analyzer. */
internal fun lspCompletionCategory(kind: Int): String = when (kind) {
    2 -> "method"
    3 -> "function"
    4 -> "constructor"
    5 -> "field"
    6 -> "variable"
    7 -> "class"
    8 -> "interface"
    9 -> "module"
    10 -> "property"
    11 -> "unit"
    12 -> "value"
    13 -> "enum"
    14 -> "keyword"
    15 -> "snippet"
    16 -> "color"
    17 -> "file"
    18 -> "reference"
    19 -> "folder"
    20 -> "enum member"
    21 -> "constant"
    22 -> "struct"
    23 -> "event"
    24 -> "operator"
    25 -> "type"
    else -> ""
}

/**
 * Shared completion relevance for every language.
 * Exact/prefix matches win, followed by contained and subsequence matches.
 */
internal fun completionMatchScore(label: String, query: String): Int? {
    if (query.isEmpty()) return 0
    val candidate = label.lowercase()
    val needle = query.lowercase()
    if (candidate == needle) return 0
    if (candidate.startsWith(needle)) return 10 + (candidate.length - needle.length).coerceAtMost(80)
    val containedAt = candidate.indexOf(needle)
    if (containedAt >= 0) return 150 + containedAt * 4 + candidate.length - needle.length

    var candidateIndex = 0
    var gapCost = 0
    for (character in needle) {
        val found = candidate.indexOf(character, candidateIndex)
        if (found < 0) return null
        gapCost += found - candidateIndex
        candidateIndex = found + 1
    }
    return 300 + gapCost * 3 + candidate.length - needle.length
}

/** Lightweight, dependency-free highlighting and diagnostic decoration. */
internal class CodeVisualTransformation(
    private val diagnostics: List<CodeDiagnostic>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val styled = AnnotatedString.Builder(source)
        fun color(pattern: Regex, value: Color) = pattern.findAll(source).forEach {
            styled.addStyle(SpanStyle(color = value), it.range.first, it.range.last + 1)
        }
        color(Regex("\\b(alignas|auto|bool|break|case|catch|char|class|const|constexpr|continue|default|delete|do|double|else|enum|explicit|extern|false|float|for|if|inline|int|namespace|new|noexcept|nullptr|private|protected|public|return|short|signed|sizeof|static|struct|switch|template|this|throw|true|try|typedef|typename|union|unsigned|using|virtual|void|volatile|while)\\b"), Color(0xFFC586C0))
        color(Regex("\\b(std|uint|uint8_t|uint16_t|uint32_t|uint64_t|size_t|string|vector)\\b"), Color(0xFF4EC9B0))
        color(Regex("\\b(0x[0-9a-fA-F]+|[0-9]+(?:\\.[0-9]+)?)\\b"), Color(0xFFB5CEA8))
        color(Regex("(?m)^\\s*#.*$"), Color(0xFFC586C0))
        color(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), Color(0xFFCE9178))
        color(Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/"), Color(0xFF6A9955))
        diagnostics.filter { it.severity == "error" || it.severity == "warning" }.forEach { diagnostic ->
            val lineStart = source.lineSequence().take((diagnostic.line - 1).coerceAtLeast(0)).sumOf { it.length + 1 }
            val start = (lineStart + diagnostic.column - 1).coerceIn(0, source.length)
            if (start < source.length) {
                val end = generateSequence(start) { it + 1 }
                    .takeWhile { it < source.length && (source[it].isLetterOrDigit() || source[it] == '_') }
                    .lastOrNull()?.plus(1) ?: (start + 1)
                styled.addStyle(
                    SpanStyle(
                        color = if (diagnostic.severity == "error") Color(0xFFFF8A80) else Color(0xFFFFD180),
                        textDecoration = TextDecoration.Underline,
                    ),
                    start,
                    end.coerceAtMost(source.length),
                )
            }
        }
        return TransformedText(styled.toAnnotatedString(), OffsetMapping.Identity)
    }
}

internal data class BracketGuide(
    val openingOffset: Int,
    val closingOffset: Int,
    val indentationOffset: Int,
)

internal fun bracketGuides(source: String): List<BracketGuide> {
    val stack = ArrayDeque<Int>()
    val result = mutableListOf<BracketGuide>()
    var quoted: Char? = null
    var escaped = false
    var lineComment = false
    var blockComment = false
    source.forEachIndexed { offset, char ->
        if (char == '\n') {
            lineComment = false
            if (quoted != null) quoted = null
            escaped = false
            return@forEachIndexed
        }
        if (lineComment) return@forEachIndexed
        if (blockComment) {
            if (offset > 0 && source[offset - 1] == '*' && char == '/') blockComment = false
            return@forEachIndexed
        }
        if (quoted != null) {
            if (!escaped && char == quoted) quoted = null
            escaped = !escaped && char == '\\'
            return@forEachIndexed
        }
        if (char == '/' && source.getOrNull(offset + 1) == '/') lineComment = true
        else if (char == '/' && source.getOrNull(offset + 1) == '*') blockComment = true
        else if (char == '\"' || char == '\'') quoted = char
        else if (char == '{') stack.addLast(offset)
        else if (char == '}' && stack.isNotEmpty()) {
            val opening = stack.removeLast()
            if ('\n' in source.substring(opening, offset)) {
                val openingLineStart = source.lastIndexOf('\n', (opening - 1).coerceAtLeast(0))
                    .let { if (it < 0) 0 else it + 1 }
                val openingIndent = (openingLineStart..opening)
                    .firstOrNull { !source[it].isWhitespace() }
                    ?: opening
                result += BracketGuide(opening, offset, openingIndent)
            }
        }
    }
    return result
}

private val inlineDiagnosticPattern = Regex(
    "^(.+?):\\s*(\\d+)(?::(\\d+))?:\\s*(error|warning|note|fatal error):\\s*(.+)$",
    RegexOption.IGNORE_CASE,
)
private val gnuDiagnosticLocationPattern = Regex("^(.+?):(\\d+):(\\d+):?$")
private val gnuDiagnosticMessagePattern = Regex(
    "^(error|warning|note|fatal error):\\s*(.+)$",
    RegexOption.IGNORE_CASE,
)

/** Parses Clang-style one-line diagnostics plus GNU Fortran's split location/message format. */
internal fun parseDiagnostics(output: String): List<CodeDiagnostic> {
    val lines = output.lineSequence().map(String::trim).toList()
    return buildList {
        lines.forEachIndexed { index, line ->
            inlineDiagnosticPattern.matchEntire(line)?.let { match ->
                add(
                    CodeDiagnostic(
                        file = match.groupValues[1].substringAfterLast('/'),
                        line = match.groupValues[2].toIntOrNull() ?: 1,
                        column = match.groupValues[3].toIntOrNull() ?: 1,
                        severity = match.groupValues[4].lowercase().removePrefix("fatal "),
                        message = match.groupValues[5],
                    ),
                )
                return@forEachIndexed
            }
            val location = gnuDiagnosticLocationPattern.matchEntire(line) ?: return@forEachIndexed
            val message = lines.asSequence()
                .drop(index + 1)
                .take(5)
                .mapNotNull(gnuDiagnosticMessagePattern::matchEntire)
                .firstOrNull()
                ?: return@forEachIndexed
            add(
                CodeDiagnostic(
                    file = location.groupValues[1].substringAfterLast('/'),
                    line = location.groupValues[2].toIntOrNull() ?: 1,
                    column = location.groupValues[3].toIntOrNull() ?: 1,
                    severity = message.groupValues[1].lowercase().removePrefix("fatal "),
                    message = message.groupValues[2],
                ),
            )
        }
    }.distinct()
}

internal fun completionItems(source: String, fileName: String = ""): List<CompletionItem> {
    if (isCppIntelligenceFile(fileName)) {
        return cFamilyCompletionItems(
            source = source,
            fileName = fileName,
            picoProject = source.contains("pico/") || source.contains("hardware/"),
        )
    }
    val projectNames = Regex("\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b").findAll(source)
        .map { it.value }
        .distinct()
        .map(::CompletionItem)
        .toList()
    val languageItems = when {
        isFortranSource(fileName) -> fortranCompletions
        isCobolSource(fileName) -> cobolCompletions
        isAssemblySource(fileName) -> assemblyCompletionItems(emptyMap())
        else -> emptyList()
    }
    return (languageItems + projectNames).distinctBy { it.label }
}

/**
 * Lightweight C-family candidates shown while clangd is starting or while a
 * Pico project has not generated compile_commands.json yet. clangd results
 * are merged into these candidates as soon as semantic analysis is ready.
 */
internal fun cFamilyCompletionItems(
    source: String,
    fileName: String,
    picoProject: Boolean,
): List<CompletionItem> {
    val projectNames = Regex("\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b").findAll(source)
        .map { it.value }
        .distinct()
        .map(::CompletionItem)
        .toList()
    val languageItems = if (fileName.endsWith(".c", ignoreCase = true)) {
        cCompletions
    } else {
        cppCompletions
    }
    return ((if (picoProject) picoCompletions else emptyList()) + languageItems + projectNames)
        .distinctBy { it.label }
}

/** Project-local headers offered for a quoted C/C++ include. */
internal fun projectHeaderCompletions(
    source: String,
    cursor: Int,
    activeFile: String,
    projectFiles: Set<String>,
): List<CompletionItem> {
    val before = source.take(cursor.coerceIn(0, source.length))
    val typedPath = Regex("^\\s*#\\s*include\\s*\"([^\"]*)$")
        .matchEntire(before.substringAfterLast('\n'))
        ?.groupValues
        ?.get(1)
        ?: return emptyList()
    val typedDirectory = typedPath.substringBeforeLast('/', "")
    val typedName = typedPath.substringAfterLast('/')
    val extensions = setOf("h", "hh", "hpp", "hxx", "inc", "inl", "ipp", "tpp")
    return projectFiles.asSequence()
        .map { it.replace('\\', '/').removePrefix("./") }
        .filter { it != activeFile && it.substringAfterLast('.', "").lowercase() in extensions }
        .map { path -> if (path.startsWith("include/")) path.removePrefix("include/") else path }
        .filter { candidate ->
            val directoryMatches = typedDirectory.isEmpty() || candidate.startsWith("$typedDirectory/")
            directoryMatches && candidate.substringAfterLast('/').startsWith(typedName, ignoreCase = true)
        }
        .distinct()
        .sortedWith(compareBy<String> { it.count { char -> char == '/' } }.thenBy(String::lowercase))
        .map { candidate ->
            // Sora replaces only the part after the last slash.
            CompletionItem(candidate, "${candidate.substringAfterLast('/')}\"", "Project header")
        }
        .take(80)
        .toList()
}

/**
 * Keep project-library completion useful while clangd is rebuilding a preamble.
 *
 * clangd remains authoritative for types and overloads. This small index only
 * contributes declarations from headers that the active file actually includes,
 * so a newly added local library is immediately visible instead of requiring a
 * build or a second edit before its first suggestions appear.
 */
internal fun includedCppSymbolCompletions(
    source: String,
    activeFile: String,
    projectFiles: Map<String, String>,
): List<CompletionItem> {
    val normalized = projectFiles.mapKeys { it.key.replace('\\', '/').removePrefix("./") }
    val activeDirectory = activeFile.replace('\\', '/').substringBeforeLast('/', "")
    val includePattern = Regex("(?m)^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]")
    val pending = ArrayDeque<String>()
    includePattern.findAll(source).forEach { pending += it.groupValues[1] }
    val visited = linkedSetOf<String>()
    val headers = mutableListOf<Pair<String, String>>()

    fun resolve(include: String): String? {
        val candidates = buildList {
            if (activeDirectory.isNotEmpty()) add("$activeDirectory/$include")
            add(include)
            add("include/$include")
        }
        candidates.firstOrNull(normalized::containsKey)?.let { return it }
        return normalized.keys.singleOrNull { it.endsWith("/$include") }
    }

    while (pending.isNotEmpty() && visited.size < 64) {
        val requested = pending.removeFirst()
        val path = resolve(requested) ?: continue
        if (!visited.add(path)) continue
        val text = normalized[path] ?: continue
        headers += path to text
        includePattern.findAll(text).forEach { pending += it.groupValues[1] }
    }

    val ignoredFunctions = setOf("if", "for", "while", "switch", "catch", "sizeof", "alignof")
    return headers.flatMap { (path, text) ->
        buildList {
            Regex("\\b(?:class|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(text).forEach {
                val name = it.groupValues[1]
                add(CompletionItem(name, name, "$name · $path", "class"))
            }
            Regex("\\benum(?:\\s+class)?\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(text).forEach {
                val name = it.groupValues[1]
                add(CompletionItem(name, name, "$name · $path", "enum"))
            }
            Regex("(?m)^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)").findAll(text).forEach {
                val name = it.groupValues[1]
                add(CompletionItem(name, name, "$name · $path", "macro"))
            }
            Regex(
                "(?m)^[\\t ]*(?:template\\s*<[^;{}]+>\\s*)?(?:[A-Za-z_][A-Za-z0-9_:<>]*[\\t &*]+)+" +
                    "([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*(?:const\\s*)?(?:noexcept\\s*)?(?:;|\\{)",
            ).findAll(text).forEach {
                val name = it.groupValues[1]
                if (name !in ignoredFunctions) {
                    add(CompletionItem(name, "$name(", "$name(…) · $path", "function"))
                }
            }
        }
    }.distinctBy { it.label }.take(160)
}

private val fortranCompletions = listOf(
    "program", "module", "submodule", "subroutine", "function", "contains", "use", "only", "implicit none",
    "integer", "real", "complex", "logical", "character", "type", "class", "parameter", "allocatable",
    "dimension", "intent", "optional", "pointer", "target", "public", "private", "interface", "procedure",
    "allocate", "deallocate", "if", "then", "else", "end if", "select case", "case", "do", "do concurrent",
    "end do", "where", "forall", "associate", "block", "call", "return", "stop", "error stop", "print",
    "read", "write", "open", "close", "inquire", "format",
).map { CompletionItem(it.substringBefore(' '), it, "Fortran keyword") } + listOf(
    CompletionItem("iso_fortran_env", "iso_fortran_env", "Fortran intrinsic module"),
    CompletionItem("iso_c_binding", "iso_c_binding", "Fortran C interoperability module"),
)

private val cobolCompletions = listOf(
    "IDENTIFICATION DIVISION", "PROGRAM-ID", "ENVIRONMENT DIVISION", "DATA DIVISION", "FILE SECTION",
    "WORKING-STORAGE SECTION", "LINKAGE SECTION", "PROCEDURE DIVISION", "PIC", "PICTURE", "VALUE",
    "DISPLAY", "ACCEPT", "MOVE", "COMPUTE", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "IF", "ELSE",
    "END-IF", "EVALUATE", "WHEN", "PERFORM", "UNTIL", "VARYING", "CALL", "USING", "RETURNING",
    "OPEN", "READ", "WRITE", "REWRITE", "CLOSE", "STRING", "UNSTRING", "INSPECT", "STOP RUN",
).map { value -> CompletionItem(value.substringBefore(' '), value, "COBOL keyword") }

private val picoCompletions = listOf(
    CompletionItem("gpio_init", "gpio_init(", "void gpio_init(uint gpio) · hardware/gpio.h"),
    CompletionItem("gpio_set_dir", "gpio_set_dir(", "void gpio_set_dir(uint gpio, bool out) · hardware/gpio.h"),
    CompletionItem("gpio_put", "gpio_put(", "void gpio_put(uint gpio, bool value) · hardware/gpio.h"),
    CompletionItem("gpio_get", "gpio_get(", "bool gpio_get(uint gpio) · hardware/gpio.h"),
    CompletionItem("gpio_pull_up", "gpio_pull_up(", "void gpio_pull_up(uint gpio) · hardware/gpio.h"),
    CompletionItem("gpio_pull_down", "gpio_pull_down(", "void gpio_pull_down(uint gpio) · hardware/gpio.h"),
    CompletionItem("gpio_set_irq_enabled_with_callback", "gpio_set_irq_enabled_with_callback(", "GPIO interrupt callback · hardware/gpio.h"),
    CompletionItem("sleep_ms", "sleep_ms(", "void sleep_ms(uint32_t ms) · pico/time.h"),
    CompletionItem("sleep_us", "sleep_us(", "void sleep_us(uint64_t us) · pico/time.h"),
    CompletionItem("stdio_init_all", "stdio_init_all()", "bool stdio_init_all(void) · pico/stdio.h"),
    CompletionItem("stdio_usb_init", "stdio_usb_init()", "bool stdio_usb_init(void) · pico/stdio_usb.h"),
    CompletionItem("tud_task", "tud_task()", "Run TinyUSB device task · tusb.h"),
    CompletionItem("tud_cdc_write", "tud_cdc_write(", "uint32_t tud_cdc_write(const void*, uint32_t) · tusb.h"),
    CompletionItem("tud_cdc_write_flush", "tud_cdc_write_flush()", "Flush TinyUSB CDC output · tusb.h"),
    CompletionItem("uart_init", "uart_init(", "uint uart_init(uart_inst_t*, uint baudrate) · hardware/uart.h"),
    CompletionItem("uart_puts", "uart_puts(", "void uart_puts(uart_inst_t*, const char*) · hardware/uart.h"),
    CompletionItem("uart_write_blocking", "uart_write_blocking(", "Write bytes to UART · hardware/uart.h"),
    CompletionItem("uart_read_blocking", "uart_read_blocking(", "Read bytes from UART · hardware/uart.h"),
    CompletionItem("i2c_init", "i2c_init(", "uint i2c_init(i2c_inst_t*, uint baudrate) · hardware/i2c.h"),
    CompletionItem("i2c_write_blocking", "i2c_write_blocking(", "Blocking I²C write · hardware/i2c.h"),
    CompletionItem("i2c_read_blocking", "i2c_read_blocking(", "Blocking I²C read · hardware/i2c.h"),
    CompletionItem("spi_init", "spi_init(", "uint spi_init(spi_inst_t*, uint baudrate) · hardware/spi.h"),
    CompletionItem("spi_write_blocking", "spi_write_blocking(", "Blocking SPI write · hardware/spi.h"),
    CompletionItem("spi_read_blocking", "spi_read_blocking(", "Blocking SPI read · hardware/spi.h"),
    CompletionItem("pwm_gpio_to_slice_num", "pwm_gpio_to_slice_num(", "Find PWM slice for GPIO · hardware/pwm.h"),
    CompletionItem("pwm_set_wrap", "pwm_set_wrap(", "Set PWM counter wrap · hardware/pwm.h"),
    CompletionItem("pwm_set_gpio_level", "pwm_set_gpio_level(", "Set GPIO PWM level · hardware/pwm.h"),
    CompletionItem("pwm_set_enabled", "pwm_set_enabled(", "Enable a PWM slice · hardware/pwm.h"),
    CompletionItem("irq_set_exclusive_handler", "irq_set_exclusive_handler(", "Install exclusive IRQ handler · hardware/irq.h"),
    CompletionItem("irq_set_enabled", "irq_set_enabled(", "Enable or disable IRQ · hardware/irq.h"),
    CompletionItem("multicore_launch_core1", "multicore_launch_core1(", "Launch function on core 1 · pico/multicore.h"),
    CompletionItem("multicore_fifo_push_blocking", "multicore_fifo_push_blocking(", "Push inter-core FIFO word · pico/multicore.h"),
    CompletionItem("multicore_fifo_pop_blocking", "multicore_fifo_pop_blocking()", "Pop inter-core FIFO word · pico/multicore.h"),
    CompletionItem("pio_add_program", "pio_add_program(", "Load a pioasm program · hardware/pio.h"),
    CompletionItem("dma_channel_configure", "dma_channel_configure(", "Configure DMA transfer · hardware/dma.h"),
    CompletionItem("cyw43_arch_init", "cyw43_arch_init()", "Initialize Pico W CYW43 architecture · pico/cyw43_arch.h"),
    CompletionItem("cyw43_arch_enable_sta_mode", "cyw43_arch_enable_sta_mode()", "Enable Pico W station mode · pico/cyw43_arch.h"),
    CompletionItem("cyw43_arch_wifi_connect_timeout_ms", "cyw43_arch_wifi_connect_timeout_ms(", "Connect Pico W to Wi-Fi · pico/cyw43_arch.h"),
    CompletionItem("cyw43_arch_gpio_put", "cyw43_arch_gpio_put(", "Set Pico W CYW43 GPIO · pico/cyw43_arch.h"),
    CompletionItem("tcp_new", "tcp_new()", "Create an lwIP TCP PCB · lwip/tcp.h"),
    CompletionItem("tcp_connect", "tcp_connect(", "Connect an lwIP TCP PCB · lwip/tcp.h"),
    CompletionItem("udp_new", "udp_new()", "Create an lwIP UDP PCB · lwip/udp.h"),
    CompletionItem("PICO_DEFAULT_LED_PIN"), CompletionItem("GPIO_OUT"), CompletionItem("GPIO_IN"),
    CompletionItem("uart0"), CompletionItem("uart1"), CompletionItem("i2c0"), CompletionItem("i2c1"),
    CompletionItem("spi0"), CompletionItem("spi1"),
)

private val cppCompletions = listOf(
    "alignas", "auto", "bool", "break", "case", "catch", "char", "class", "const", "constexpr", "continue",
    "default", "delete", "do", "double", "else", "enum", "explicit", "extern", "false", "float", "for",
    "if", "inline", "int", "namespace", "new", "noexcept", "nullptr", "private", "protected", "public",
    "return", "short", "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw", "true",
    "try", "typedef", "typename", "uint", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "union", "unsigned",
    "using", "virtual", "void", "volatile", "while",
).map(::CompletionItem)

private val cCompletions = listOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
    "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return",
    "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
    "volatile", "while", "_Alignas", "_Alignof", "_Atomic", "_Bool", "_Complex", "_Generic",
    "_Noreturn", "_Static_assert", "_Thread_local", "bool", "false", "true", "uint", "uint8_t",
    "uint16_t", "uint32_t", "uint64_t", "size_t",
).map(::CompletionItem)
