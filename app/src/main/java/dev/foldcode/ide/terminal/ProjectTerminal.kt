package dev.foldcode.ide

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/** A persistent, project-scoped shell with live stdin/stdout streaming. */
internal class ProjectTerminal(private val context: Context) {
    private val process = AtomicReference<Process?>(null)
    private val projectRoot = AtomicReference<File?>(null)
    private val workingDirectory = AtomicReference<File?>(null)
    private val writerLock = Any()
    private val npmEnvironment = NpmTerminalEnvironment(context.filesDir, context.cacheDir)

    @Synchronized
    fun start(
        projectDirectory: File,
        initialWorkingDirectory: File = projectDirectory,
        onOutput: (String) -> Unit,
        onCommandFinished: (Int, File) -> Unit,
        onExit: () -> Unit,
    ) {
        close()
        val rootDirectory = projectDirectory.canonicalFile
        if (!rootDirectory.isDirectory) return
        val directory = runCatching { initialWorkingDirectory.canonicalFile }
            .getOrDefault(rootDirectory)
            .takeIf(File::isDirectory)
            ?: rootDirectory
        projectRoot.set(rootDirectory)
        this.workingDirectory.set(directory)
        val terminalBin = File(context.filesDir, "terminal-bin").apply { mkdirs() }
        installToolLinks(terminalBin)
        val shell = ProcessBuilder("/system/bin/sh")
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                npmEnvironment.configure(environment())
                environment()["HOME"] = rootDirectory.absolutePath
                environment()["FOLDCODE_PROJECT_ROOT"] = rootDirectory.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["FOLDCODE_PYTHON_ROOT"] = File(context.filesDir, "python-runtime").absolutePath
                environment()["PIP_FIND_LINKS"] =
                    File(context.filesDir, "python-runtime/foldcode/wheelhouse").absolutePath
                // Android wheels are still uncommon. Prefer a known-compatible
                // binary from the extension over a newer desktop-oriented sdist.
                environment()["PIP_PREFER_BINARY"] = "1"
                environment()["FOLDCODE_WEB_ROOT"] = File(context.filesDir, "web-runtime").absolutePath
                environment()["NODE_PATH"] =
                    File(context.filesDir, "web-runtime/lib/node_modules").absolutePath
                environment()["PYTHONPATH"] = File(rootDirectory, ".foldcode/python").absolutePath
                environment()["PYTHONPYCACHEPREFIX"] = File(context.cacheDir, "python-pycache").apply { mkdirs() }.absolutePath
                environment()["LD_LIBRARY_PATH"] =
                    listOf(
                        File(context.filesDir, "cpp-runtime/native").absolutePath,
                        File(context.filesDir, "web-runtime/lib").absolutePath,
                        context.applicationInfo.nativeLibraryDir,
                    ).joinToString(":")
                environment()["FOLDCODE_LLD_CORE"] =
                    File(context.filesDir, "cpp-runtime/native/libfoldlldcore.so").absolutePath
                environment()["FOLDCODE_PICOTOOL_ROOT"] = File(context.filesDir, "pico-tools-runtime").absolutePath
                File(context.filesDir, "gnu-arm-runtime").takeIf(File::isDirectory)?.let { runtime ->
                    environment()["FOLDCODE_GNU_ARM_ROOT"] = runtime.absolutePath
                }
                NativeGit(context).configure(environment(), terminalBin)
            }
            .start()
        process.set(shell)
        Thread({
            try {
                val reader = InputStreamReader(shell.inputStream, Charsets.UTF_8)
                val buffer = CharArray(1024)
                val pending = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        pending.append(buffer, 0, count)
                        drainOutput(pending, onOutput, onCommandFinished)
                    }
                }
                if (pending.isNotEmpty()) onOutput(pending.toString())
            } catch (error: IOException) {
                // close() destroys the previous shell when a project or extension
                // change restarts the terminal. Android reports the blocked read as
                // InterruptedIOException; that is normal shutdown, not an app crash.
                if (process.get() === shell) onOutput("Terminal stopped: ${error.message}\n")
            } finally {
                process.compareAndSet(shell, null)
                onExit()
            }
        }, "FoldCode-project-terminal").apply { isDaemon = true }.start()
    }

    fun sendCommand(line: String): Boolean {
        val command = line.takeIf(String::isNotBlank) ?: ":"
        val repairGlobalNpmLaunchers = if (command.trimStart().matches(Regex("^npm(?:\\s.*)?$"))) {
            npmEnvironment.repairGlobalLaunchersCommand() + "; "
        } else {
            ""
        }
        return writeLine(
            "${prepareCommand(command)}; " +
                "foldcode_status=\$?; " +
                repairGlobalNpmLaunchers +
                "foldcode_pwd=\"\$(pwd 2>/dev/null)\"; " +
                "printf '\\036FOLDCODE_DONE:%s\\t%s\\037' \"\$foldcode_status\" \"\$foldcode_pwd\"",
        )
    }

    fun sendInput(line: String): Boolean = writeLine(line)

    /** User-facing help only advertises commands that this installation can execute. */
    fun helpText(listAll: Boolean): String {
        val shellBuiltins = listOf(
            "cd", "pwd", "echo", "printf", "read", "set", "unset", "export", "env",
            "alias", "true", "false", "test", "clear", "help",
        )
        val fileCommands = availableCommands(
            "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "touch", "find", "grep",
            "sed", "awk", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "xargs",
            "basename", "dirname", "chmod", "stat", "du", "df", "date", "which", "nl", "tee",
            "readlink", "realpath", "ln", "dd", "cmp", "comm", "diff", "paste", "split",
            "strings", "od", "hexdump", "xxd", "printenv",
        )
        val archiveCommands = availableCommands("tar", "gzip", "gunzip", "zip", "unzip", "sha256sum", "md5sum")
        val systemCommands = availableCommands(
            "ps", "top", "kill", "killall", "pgrep", "pkill", "sleep", "time", "timeout",
            "uptime", "uname", "id", "whoami", "getconf", "expr", "seq", "yes",
        )
        val networkCommands = availableCommands(
            "curl", "ping", "ping6", "nslookup", "host", "netstat", "ifconfig", "ip", "route", "traceroute",
        )
        val developmentCommands = availableCommands(
            "clang", "clang++", "ld.lld", "git", "python", "python3", "pip", "pip3", "node", "npm", "npx",
            "arm-none-eabi-gcc", "arm-none-eabi-g++", "arm-none-eabi-as", "arm-none-eabi-ld",
            "arm-none-eabi-ar", "arm-none-eabi-objcopy", "arm-none-eabi-objdump", "arm-none-eabi-size",
        ) + listOfNotNull(
            "picotool".takeIf { File(context.filesDir, "pico-tools-runtime").isDirectory },
        )
        if (!listAll) {
            return buildString {
                appendLine("FoldCode project terminal")
                appendLine("Run compilers, scripts, Git and ordinary shell commands in the open project.")
                appendLine()
                appendLine("  help -l    list available commands")
                appendLine("  clear      clear the terminal transcript")
                appendLine("  cd DIR     change the current directory")
                appendLine("  pwd        print the current directory")
                appendLine("  COMMAND | COMMAND and > FILE are supported")
                append("Use the terminal's + control to start a fresh shell session.")
            }
        }
        return buildString {
            appendLine("Available FoldCode terminal commands")
            appendCommandGroup("Shell", shellBuiltins)
            appendCommandGroup("Files and text", fileCommands)
            appendCommandGroup("Archives", archiveCommands)
            appendCommandGroup("Processes and system", systemCommands)
            appendCommandGroup("Network", networkCommands)
            appendCommandGroup("Development", developmentCommands.distinct())
            append("Additional Android commands may exist but are not part of FoldCode's supported terminal surface.")
        }
    }

    private fun availableCommands(vararg names: String): List<String> {
        val terminalBin = File(context.filesDir, "terminal-bin")
        return names.filter { name ->
            File(terminalBin, name).exists() || File("/system/bin", name).canExecute() ||
                File("/system/xbin", name).canExecute()
        }
    }

    private fun StringBuilder.appendCommandGroup(title: String, commands: List<String>) {
        if (commands.isEmpty()) return
        append(title).append(":\n  ")
        append(commands.chunked(6).joinToString("\n  ") { it.joinToString("  ") })
        append('\n')
    }

    private fun writeLine(line: String): Boolean {
        val shell = process.get() ?: return false
        return runCatching {
            synchronized(writerLock) {
                shell.outputStream.bufferedWriter().apply {
                    write(line)
                    newLine()
                    flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun drainOutput(
        pending: StringBuilder,
        onOutput: (String) -> Unit,
        onCommandFinished: (Int, File) -> Unit,
    ) {
        while (pending.isNotEmpty()) {
            val start = pending.indexOf(COMMAND_MARKER)
            if (start >= 0) {
                if (start > 0) onOutput(pending.substring(0, start))
                val end = pending.indexOf(COMMAND_MARKER_END, start + COMMAND_MARKER.length)
                if (end < 0) {
                    pending.delete(0, start)
                    return
                }
                val payload = pending.substring(start + COMMAND_MARKER.length, end)
                val status = payload.substringBefore('\t').toIntOrNull() ?: -1
                val reportedDirectory = payload.substringAfter('\t', "")
                    .takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.let { runCatching { it.canonicalFile }.getOrNull() }
                    ?.takeIf(File::isDirectory)
                    ?: workingDirectory.get()
                    ?: projectRoot.get()
                    ?: File("/")
                workingDirectory.set(reportedDirectory)
                pending.delete(0, end + COMMAND_MARKER_END.length)
                onCommandFinished(status, reportedDirectory)
                continue
            }
            val heldSuffix = markerPrefixSuffixLength(pending)
            val emitCount = pending.length - heldSuffix
            if (emitCount > 0) {
                onOutput(pending.substring(0, emitCount))
                pending.delete(0, emitCount)
            }
            return
        }
    }

    private fun markerPrefixSuffixLength(value: CharSequence): Int {
        val maximum = minOf(value.length, COMMAND_MARKER.length - 1)
        for (length in maximum downTo 1) {
            if (value.substring(value.length - length) == COMMAND_MARKER.substring(0, length)) return length
        }
        return 0
    }

    @Synchronized
    fun close() {
        process.getAndSet(null)?.terminateTree()
    }

    private fun installToolLinks(directory: File) {
        val tools = mutableMapOf(
            "python" to "foldpython.so",
            "python3" to "foldpython.so",
            "pip" to "foldpython.so",
            "pip3" to "foldpython.so",
            "clang" to "libfoldclang.so",
            "clang++" to "libfoldclang.so",
            "ld.lld" to "foldlld.so",
        )
        if (File(context.filesDir, "web-runtime/bin/node").isFile ||
            File(context.filesDir, "web-runtime/lib/libnode.so").isFile
        ) {
            tools["node"] = "foldnode.so"
            tools["npm"] = "foldnode.so"
            tools["npx"] = "foldnode.so"
        }
        tools["git"] = "foldgit.so"
        // The GNU Arm payload is optional, but once installed its complete
        // binutils surface should behave like an ordinary command-line
        // toolchain. The launcher selects the actual tool from argv[0].
        if (File(context.filesDir, "gnu-arm-runtime/toolchain/bin/arm-none-eabi-as").isFile) {
            listOf(
                "gcc", "g++", "c++", "cpp", "as", "ld", "ld.bfd", "ar", "ranlib", "nm",
                "objcopy", "objdump", "readelf", "size", "strings", "strip",
            ).forEach { tool -> tools["arm-none-eabi-$tool"] = "foldgnuarm.so" }
        }
        tools.forEach { (command, packagedName) ->
            val target = File(context.applicationInfo.nativeLibraryDir, packagedName)
            val link = File(directory, command)
            runCatching {
                if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
                if (target.isFile) Files.createSymbolicLink(link.toPath(), target.toPath())
            }
        }
    }

    /**
     * User-facing commands stay conventional while FoldCode supplies Android's
     * target/sysroot details and loads generated executables through linker64.
     */
    private fun prepareCommand(command: String): String {
        val trimmed = command.trimStart()
        Regex("^cd(?:\\s+(.*))?$").matchEntire(trimmed)?.let { match ->
            val root = projectRoot.get()?.canonicalFile ?: return command
            val rawDestination = match.groupValues.getOrElse(1) { "" }.trim()
            if (rawDestination.isBlank() || rawDestination == "~") {
                return "cd ${shellQuote(root.absolutePath)}"
            }
            // First preserve normal shell behaviour relative to the current
            // directory. If that path is absent, resolve the same name from
            // the open project root so Explorer paths work after any prior cd.
            val plainDestination = rawDestination
                .removeSurrounding("'")
                .removeSurrounding("\"")
            if (plainDestination.startsWith("/") || plainDestination == ".." ||
                plainDestination.startsWith("../")
            ) {
                return "cd $rawDestination"
            }
            val projectDestination = File(root, plainDestination).canonicalFile
            return "cd $rawDestination 2>/dev/null || cd ${shellQuote(projectDestination.absolutePath)}"
        }
        Regex("^ls(?:\\s+(.*))?$").matchEntire(trimmed)?.let { match ->
            val arguments = match.groupValues.getOrElse(1) { "" }.trim()
            val toybox = File("/system/bin/toybox")
            if (toybox.canExecute()) {
                return buildString {
                    append(shellQuote(toybox.absolutePath)).append(" ls")
                    if (arguments.isNotBlank()) append(' ').append(arguments)
                }
            }
        }
        Regex("^npm(?:\\s+(.*))?$").matchEntire(trimmed)?.let { match ->
            val project = workingDirectory.get()
            if (project != null && File(project, "package.json").isFile) {
                val arguments = match.groupValues.getOrElse(1) { "" }.trim()
                // npm's interactive funding tree collapses to only the root
                // package when stdout is our pipe-backed Android terminal.
                // Its JSON mode is complete, so format that same read-only
                // report into a stable terminal-friendly list. Explicit JSON
                // requests are left untouched for scripts and power users.
                val terminalArguments = if (arguments == "fund") {
                    "fund --json | node ${shellQuote(ensureNpmFundFormatter().absolutePath)}"
                } else {
                    arguments
                }
                val mutatesProject = npmCommandMutatesProject(arguments)
                return WebProjectRuntime(context).prepareForTerminalCommand(
                    projectKey = project.absolutePath,
                    sourceDirectory = project,
                    refreshProject = mutatesProject || isWebServerNpmArguments(arguments),
                )
                    .npmCommand(
                        arguments = terminalArguments,
                        sourceDirectory = project,
                        syncProjectManifests = mutatesProject,
                    )
            }
        }
        preparePythonPackageCommand(trimmed)?.let { return it }
        val executable = Regex("^\\./([^\\s;&|]+)(.*)$").matchEntire(trimmed)
        if (executable != null) {
            // Shared storage is mounted no-exec and cannot be mmap'd as executable.
            // Copy the ELF into app cache, then invoke Android's linker explicitly.
            val path = executable.groupValues[1].replace("\\", "\\\\").replace("\"", "\\\"")
            val cachedName = executable.groupValues[1].replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val cached = "\$TMPDIR/foldcode-terminal-$cachedName"
            return "cp \"\$PWD/$path\" \"$cached\" && chmod 700 \"$cached\" && " +
                "/system/bin/linker64 \"$cached\"${executable.groupValues[2]}"
        }

        val compilerMatch = Regex("^(clang\\+\\+|clang)(?:\\s+(.*))?$").matchEntire(trimmed) ?: return command
        val cpp = compilerMatch.groupValues[1] == "clang++"
        val arguments = compilerMatch.groupValues.getOrElse(2) { "" }
        val toolchain = BundledToolchain(context)
        val fixed = mutableListOf(
            toolchain.clang.absolutePath,
            "--target=aarch64-linux-android26",
            "--sysroot=${toolchain.sysroot.absolutePath}",
            "-resource-dir=${toolchain.resourceDir.absolutePath}",
            "-fuse-ld=${toolchain.lld.absolutePath}",
            "-L${context.applicationInfo.nativeLibraryDir}",
        )
        if (cpp) fixed += listOf("-nostdlib++", "-std=c++20")
        val compileOnly = Regex("(^|\\s)(-c|-E|-S|-fsyntax-only)(\\s|$)").containsMatchIn(arguments)
        val runtime = if (cpp && !compileOnly) {
            val libraryDirectory = File(toolchain.sysroot, "usr/lib/aarch64-linux-android")
            listOf(
                File(libraryDirectory, "libc++_static.a").absolutePath,
                File(libraryDirectory, "libc++abi.a").absolutePath,
                "-lm",
            ).joinToString(" ", transform = ::shellQuote)
        } else ""
        return buildString {
            append(fixed.joinToString(" ", transform = ::shellQuote))
            if (arguments.isNotBlank()) append(' ').append(arguments)
            if (runtime.isNotBlank()) append(' ').append(runtime)
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun ensureNpmFundFormatter(): File {
        val formatter = File(context.cacheDir, "foldcode-npm-fund.js")
        val source = """
            let input = '';
            process.stdin.setEncoding('utf8');
            process.stdin.on('data', chunk => input += chunk);
            process.stdin.on('end', () => {
              let root;
              try {
                root = JSON.parse(input);
              } catch (error) {
                process.stderr.write(input || ('Unable to read npm funding information: ' + error.message + '\n'));
                process.exitCode = 1;
                return;
              }

              const groups = new Map();
              const seenPackages = new Set();
              const fundingUrls = value => {
                if (!value) return [];
                if (typeof value === 'string') return [value];
                if (Array.isArray(value)) return value.flatMap(fundingUrls);
                if (typeof value === 'object' && typeof value.url === 'string') return [value.url];
                return [];
              };
              const visit = dependencies => {
                if (!dependencies || typeof dependencies !== 'object') return;
                for (const [name, details] of Object.entries(dependencies)) {
                  if (!details || typeof details !== 'object') continue;
                  const label = name + (details.version ? '@' + details.version : '');
                  for (const url of fundingUrls(details.funding)) {
                    if (!groups.has(url)) groups.set(url, new Set());
                    groups.get(url).add(label);
                    seenPackages.add(label);
                  }
                  visit(details.dependencies);
                }
              };
              visit(root.dependencies);

              // Some compact npm builds report the correct funding count after
              // install/update but return an empty dependency object from
              // `npm fund --json`. Lockfile v2/v3 already contains the same
              // installed-package funding metadata, so use it as a read-only
              // fallback instead of hiding valid results from the user.
              if (groups.size === 0) {
                try {
                  const lock = JSON.parse(require('fs').readFileSync('package-lock.json', 'utf8'));
                  for (const [path, details] of Object.entries(lock.packages || {})) {
                    if (!path || !details || typeof details !== 'object') continue;
                    const marker = 'node_modules/';
                    const markerIndex = path.lastIndexOf(marker);
                    const name = details.name ||
                      (markerIndex >= 0 ? path.substring(markerIndex + marker.length) : path);
                    if (!name) continue;
                    const label = name + (details.version ? '@' + details.version : '');
                    for (const url of fundingUrls(details.funding)) {
                      if (!groups.has(url)) groups.set(url, new Set());
                      groups.get(url).add(label);
                      seenPackages.add(label);
                    }
                  }
                } catch (_) {
                  // Missing/old lockfiles simply retain npm's original result.
                }
              }

              if (groups.size === 0) {
                console.log('No installed dependencies are currently requesting funding.');
                return;
              }
              console.log('Funding information');
              for (const [url, packages] of groups) {
                console.log('\n' + url);
                for (const name of packages) console.log('  ' + name);
              }
              console.log('\n' + seenPackages.size +
                (seenPackages.size === 1 ? ' package is' : ' packages are') +
                ' looking for funding');
            });
        """.trimIndent() + "\n"
        if (!formatter.isFile || formatter.readText() != source) formatter.writeText(source)
        return formatter
    }

    private companion object {
        const val COMMAND_MARKER = "\u001eFOLDCODE_DONE:"
        const val COMMAND_MARKER_END = "\u001f"
    }
}

internal fun npmCommandRequiresSourceRefresh(arguments: String): Boolean =
    npmCommandMutatesProject(arguments) || isWebServerNpmArguments(arguments)

internal fun preparePythonPackageCommand(command: String): String? {
    val match = Regex("^(?:pip(?:3)?|python(?:3)?\\s+-m\\s+pip)(?:\\s+(.*))?$")
        .matchEntire(command.trim()) ?: return null
    val arguments = match.groupValues.getOrElse(1) { "" }.trim()
    if (arguments != "install" && !arguments.startsWith("install ")) {
        return "python -m pip${arguments.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}"
    }

    // Keep installs isolated to the open project. Explicit destination options
    // remain untouched for users and scripts that intentionally select another
    // environment.
    val hasExplicitDestination = Regex(
        "(^|\\s)(?:--target|-t|--user|--prefix|--root)(?:=|\\s|$)",
    ).containsMatchIn(arguments)
    if (hasExplicitDestination) return "python -m pip $arguments"

    val packages = arguments.removePrefix("install").trimStart()
    val target = "\$FOLDCODE_PROJECT_ROOT/.foldcode/python"
    return buildString {
        append("mkdir -p \"").append(target).append("\" && ")
        append("python -m pip install --target \"").append(target).append('"')
        if (packages.isNotBlank()) append(' ').append(packages)
    }
}

internal fun isNpmProjectMutation(command: String): Boolean {
    val match = Regex("^npm(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
        .matchEntire(command.trim()) ?: return false
    return npmCommandMutatesProject(match.groupValues.getOrElse(1) { "" })
}

internal fun isWebServerCommand(command: String): Boolean {
    val trimmed = command.trim()
    val npm = Regex("^npm(?:\\s+(.*))?$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
    if (npm != null && isWebServerNpmArguments(npm.groupValues.getOrElse(1) { "" })) return true
    return Regex("^(?:npx\\s+)?vite(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
}

private fun npmCommandMutatesProject(arguments: String): Boolean {
    val normalized = arguments.trim().lowercase()
    if (normalized.isBlank() || normalized == "version" || normalized == "--version" || normalized == "-v") {
        return false
    }
    val command = normalized.substringBefore(' ')
    val remainder = normalized.substringAfter(' ', "").trim()
    return when (command) {
        "install", "i", "add", "ci", "uninstall", "remove", "rm", "update", "up",
        "dedupe", "prune", "link", "unlink", "rebuild" -> true
        "version" -> remainder.split(Regex("\\s+")).any { it.isNotBlank() && !it.startsWith('-') }
        "pkg" -> remainder.startsWith("set ") || remainder == "set" ||
            remainder.startsWith("delete ") || remainder == "delete"
        "audit" -> remainder.split(Regex("\\s+")).firstOrNull() == "fix"
        else -> false
    }
}

private fun isWebServerNpmArguments(arguments: String): Boolean {
    val normalized = arguments.trim().lowercase().replace(Regex("\\s+"), " ")
    return normalized == "start" || normalized.startsWith("start ") ||
        normalized == "run start" || normalized.startsWith("run start ") ||
        normalized == "run dev" || normalized.startsWith("run dev ")
}
