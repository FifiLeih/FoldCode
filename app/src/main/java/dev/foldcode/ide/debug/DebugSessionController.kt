package dev.foldcode.ide

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owns one LLDB DAP session and exposes debugger state without UI dependencies. */
internal class DebugSessionController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val runtime = DebuggerRuntime(appContext)
    private var client: DapConnection? = null
    private var adapterProcess: Process? = null
    private var hostInferiorProcess: Process? = null
    private var hostServerProcess: Process? = null
    private var openOcdProcess: Process? = null
    private var webViewBridge: WebViewDevToolsBridge? = null
    private var target: DebugLaunchTarget? = null
    private var requestedBreakpoints: List<DebugBreakpoint> = emptyList()
    private var initializedEventReceived = false
    private var launchResponseReceived = false
    private var hostAwaitingExec = false
    private var configurationDone = false
    private var hostStartupThreadId: Int? = null
    private var currentThreadId: Int? = null
    private var revealExternalFramesForPause = false
    private val inferiorInputLock = Any()
    private val hostServerLog = File(appContext.cacheDir, "foldcode-host-lldb-server.log")

    @Volatile var snapshot: DebugSessionSnapshot = DebugSessionSnapshot()
        private set
    var listener: (DebugSessionSnapshot) -> Unit = {}
    var outputListener: (String) -> Unit = {}

    val active: Boolean
        get() = snapshot.status in setOf(
            DebugSessionStatus.Starting,
            DebugSessionStatus.Running,
            DebugSessionStatus.Paused,
        )

    fun start(launchTarget: DebugLaunchTarget, breakpoints: List<DebugBreakpoint>): Result<Unit> = runCatching {
        stop()
        when (launchTarget) {
            is DebugLaunchTarget.AndroidHost -> require(runtime.hostAvailable) { runtime.missingHostMessage() }
            is DebugLaunchTarget.PicoSwd -> require(runtime.picoAvailable) { runtime.missingPicoMessage() }
            is DebugLaunchTarget.WebNode, is DebugLaunchTarget.WebBrowser -> {
                val node = when (launchTarget) {
                    is DebugLaunchTarget.WebNode -> launchTarget.nodeExecutable
                    is DebugLaunchTarget.WebBrowser -> launchTarget.nodeExecutable
                    else -> error("Unsupported debug target")
                }
                val adapter = when (launchTarget) {
                    is DebugLaunchTarget.WebNode -> launchTarget.adapterScript
                    is DebugLaunchTarget.WebBrowser -> launchTarget.adapterScript
                    else -> error("Unsupported debug target")
                }
                require(node.canExecute()) { "Web extension Node.js runtime is unavailable" }
                require(adapter.isFile) { "Install the JavaScript debugger component from Web Development" }
            }
        }
        require(when (launchTarget) {
            is DebugLaunchTarget.AndroidHost -> launchTarget.executable.isFile
            is DebugLaunchTarget.PicoSwd -> launchTarget.elf.isFile
            is DebugLaunchTarget.WebNode -> launchTarget.entryFile.isFile
            is DebugLaunchTarget.WebBrowser -> true
        }) { "Debug executable is missing. Build the project first." }

        target = launchTarget
        hostAwaitingExec = launchTarget is DebugLaunchTarget.AndroidHost
        configurationDone = false
        hostStartupThreadId = null
        requestedBreakpoints = breakpoints
        publish(DebugSessionSnapshot(
            status = DebugSessionStatus.Starting,
            title = launchTarget.displayName,
            message = when (launchTarget) {
                is DebugLaunchTarget.PicoSwd -> "Starting OpenOCD and LLDB…"
                is DebugLaunchTarget.WebNode -> "Starting V8 JavaScript debugger…"
                is DebugLaunchTarget.WebBrowser -> "Connecting the browser debugger…"
                else -> "Starting LLDB…"
            },
            breakpoints = breakpoints,
        ))

        if (launchTarget is DebugLaunchTarget.WebNode || launchTarget is DebugLaunchTarget.WebBrowser) {
            startWebDebugging(launchTarget)
            return@runCatching
        }

        val remotePort = when (launchTarget) {
            is DebugLaunchTarget.AndroidHost -> startHostServer(launchTarget)
            is DebugLaunchTarget.PicoSwd -> startOpenOcd(launchTarget)
            is DebugLaunchTarget.WebNode -> error("Web debugging uses the V8 adapter")
            is DebugLaunchTarget.WebBrowser -> error("Web debugging uses the V8 adapter")
        }
        val process = ProcessBuilder(runtime.lldbDapLauncher.absolutePath)
            .directory(launchTarget.project)
            .redirectErrorStream(false)
            .apply {
                environment()["FOLDCODE_LLDB_DAP_CORE"] = runtime.lldbCore.absolutePath
                environment()["LLDB_DEBUGSERVER_PATH"] = runtime.lldbServer.absolutePath
                environment()["LD_LIBRARY_PATH"] = listOf(
                    runtime.lldbCore.parentFile?.absolutePath.orEmpty(),
                    appContext.applicationInfo.nativeLibraryDir,
                ).joinToString(":")
                environment()["HOME"] = appContext.filesDir.absolutePath
                environment()["TMPDIR"] = appContext.cacheDir.absolutePath
            }
            .start()
        adapterProcess = process
        Thread {
            consumeProcessOutput(process, "[lldb] ")
        }.apply { name = "FoldCode-LLDB-stderr"; isDaemon = true }.start()
        client = DapClient(process, ::onEvent) { exit ->
            if (active) fail("LLDB exited with code $exit")
        }
        client!!.request("initialize", JSONObject()
            .put("clientID", "foldcode")
            .put("clientName", "FoldCode")
            .put("adapterID", "lldb")
            .put("pathFormat", "path")
            .put("linesStartAt1", true)
            .put("columnsStartAt1", true)
            .put("supportsVariableType", true)
            .put("supportsRunInTerminalRequest", false)) { response ->
            if (!response.optBoolean("success", false)) {
                fail(response.failureMessage("LLDB initialization failed"))
                return@request
            }
            val launch = when (launchTarget) {
                is DebugLaunchTarget.AndroidHost -> JSONObject()
                    .put("name", launchTarget.displayName)
                    .put("type", "lldb")
                    .put("request", "attach")
                    .put("program", runtime.debuggeeLauncher.absolutePath)
                    .put("cwd", launchTarget.project.absolutePath)
                    .put("gdb-remote-port", remotePort)
                is DebugLaunchTarget.PicoSwd -> JSONObject()
                    .put("name", launchTarget.displayName)
                    .put("type", "lldb")
                    .put("request", "attach")
                    .put("program", launchTarget.elf.absolutePath)
                    .put("cwd", launchTarget.project.absolutePath)
                    .put("gdb-remote-port", remotePort)
                is DebugLaunchTarget.WebNode -> error("Web debugging uses the V8 adapter")
                is DebugLaunchTarget.WebBrowser -> error("Web debugging uses the V8 adapter")
            }
            client?.request(launch.optString("request"), launch) { launchResponse ->
                if (!launchResponse.optBoolean("success", false)) {
                    val message = launchResponse.failureMessage("LLDB could not launch the target")
                    appendConsole("[lldb] $message\n")
                    runCatching { hostServerLog.readText() }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?.let { appendConsole("[lldb-server-detail]\n$it\n") }
                    fail(message)
                } else {
                    launchResponseReceived = true
                    configureIfReady()
                }
            }
        }
    }.onFailure { error ->
        fail(error.message ?: "Could not start the debug session")
    }

    private fun startWebDebugging(target: DebugLaunchTarget) {
        require(target is DebugLaunchTarget.WebNode || target is DebugLaunchTarget.WebBrowser)
        val node = when (target) {
            is DebugLaunchTarget.WebNode -> target.nodeExecutable
            is DebugLaunchTarget.WebBrowser -> target.nodeExecutable
            else -> error("Unsupported web target")
        }
        val adapter = when (target) {
            is DebugLaunchTarget.WebNode -> target.adapterScript
            is DebugLaunchTarget.WebBrowser -> target.adapterScript
            else -> error("Unsupported web target")
        }
        val environment = when (target) {
            is DebugLaunchTarget.WebNode -> target.environment
            is DebugLaunchTarget.WebBrowser -> target.environment
            else -> emptyMap()
        }
        val inspectorPort = if (target is DebugLaunchTarget.WebNode) availableLoopbackPort() else {
            setWebViewDebuggingEnabled(true)
            WebViewDevToolsBridge().also { webViewBridge = it }.port
        }
        if (target is DebugLaunchTarget.WebNode) {
            hostInferiorProcess = ProcessBuilder(
                node.absolutePath,
                "--inspect-brk=127.0.0.1:$inspectorPort",
                target.entryFile.absolutePath,
            ).directory(target.project).redirectErrorStream(true).apply {
                environment().putAll(environment)
            }.start().also { process ->
                Thread {
                    runCatching {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { appendConsole("$it\n") }
                        }
                    }
                }.apply { name = "FoldCode-Node-debug-output"; isDaemon = true }.start()
            }
        }

        val dapPort = availableLoopbackPort()
        adapterProcess = ProcessBuilder(
            node.absolutePath,
            adapter.absolutePath,
            dapPort.toString(),
            "127.0.0.1",
        ).directory(target.project).redirectErrorStream(true).apply {
            environment().putAll(environment)
        }.start().also { process ->
            Thread { consumeProcessOutput(process, "[js-debug] ", useErrorStream = false) }
                .apply { name = "FoldCode-V8-adapter-output"; isDaemon = true }.start()
        }

        val socket = connectLoopback(dapPort)
        client = TcpDapClient(socket, ::onEvent) {
            if (active) fail("JavaScript debug adapter disconnected")
        }
        client!!.request("initialize", JSONObject()
            .put("clientID", "foldcode")
            .put("clientName", "FoldCode")
            .put("adapterID", if (target is DebugLaunchTarget.WebBrowser) "pwa-chrome" else "pwa-node")
            .put("pathFormat", "path")
            .put("linesStartAt1", true)
            .put("columnsStartAt1", true)
            .put("supportsVariableType", true)
            .put("supportsRunInTerminalRequest", false)) { response ->
            if (!response.optBoolean("success", false)) {
                fail(response.failureMessage("V8 debugger initialization failed"))
                return@request
            }
            val attach = JSONObject()
                .put("name", target.displayName)
                .put("type", if (target is DebugLaunchTarget.WebBrowser) "pwa-chrome" else "pwa-node")
                .put("request", "attach")
                .put("address", "127.0.0.1")
                .put("port", inspectorPort)
                .put("cwd", target.project.absolutePath)
                .put("sourceMaps", true)
                .put("skipFiles", JSONArray(listOf("<node_internals>/**", "**/node_modules/**")))
            if (target is DebugLaunchTarget.WebBrowser) {
                attach.put("webRoot", target.project.absolutePath)
                    .put("urlFilter", "${target.url.trimEnd('/')}/*")
            } else {
                attach.put("localRoot", target.project.absolutePath)
                    .put("remoteRoot", target.project.absolutePath)
                    .put("continueOnAttach", true)
            }
            // vscode-js-debug deliberately waits for configurationDone before
            // resolving attach. Mark launch ready when the request is sent so
            // breakpoints and configurationDone are not deadlocked behind its
            // response.
            launchResponseReceived = true
            client?.request("attach", attach) { attachResponse ->
                if (!attachResponse.optBoolean("success", false)) {
                    fail(attachResponse.failureMessage("V8 debugger could not attach to the target"))
                }
            }
            configureIfReady()
        }
    }

    private fun availableLoopbackPort(): Int = ServerSocket(0).use(ServerSocket::getLocalPort)

    private fun connectLoopback(port: Int): Socket {
        repeat(60) {
            runCatching { return Socket("127.0.0.1", port) }
            Thread.sleep(100)
        }
        error("Timed out while starting the JavaScript debug adapter")
    }

    /**
     * LLDB's desktop local-launch path synchronizes with lldb-server through a
     * duplicated anonymous pipe. That handshake is unreliable inside an
     * Android app sandbox, so FoldCode owns the server process and connects
     * LLDB-DAP to it through the standard loopback GDB Remote transport.
     */
    private fun startHostServer(target: DebugLaunchTarget.AndroidHost): Int {
        val port = ServerSocket(0).use(ServerSocket::getLocalPort)
        hostServerLog.delete()
        hostInferiorProcess = ProcessBuilder(
            buildList {
                add(runtime.debuggeeLauncher.absolutePath)
                add(target.executable.absolutePath)
                addAll(target.arguments)
            },
        )
            .directory(target.project)
            .redirectErrorStream(true)
            .apply {
                environment()["FOLDCODE_DEBUG_WAIT"] = "1"
                environment()["LD_LIBRARY_PATH"] = listOf(
                    runtime.lldbCore.parentFile?.absolutePath.orEmpty(),
                    appContext.applicationInfo.nativeLibraryDir,
                ).joinToString(":")
                environment()["HOME"] = appContext.filesDir.absolutePath
                environment()["TMPDIR"] = appContext.cacheDir.absolutePath
            }
            .start()
        val inferiorOutput = hostInferiorProcess!!.inputStream
        val inferiorHandshake = readProtocolLine(inferiorOutput)
        val inferiorPid = inferiorHandshake
            .removePrefix("FOLDCODE_DEBUG_PID ")
            .toIntOrNull()
            ?: error("FoldCode debugger could not identify the suspended process")
        Thread {
            runCatching {
                inferiorOutput.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (count > 0) appendConsole(String(buffer, 0, count))
                    }
                }
            }
        }.apply { name = "FoldCode-Debuggee-output"; isDaemon = true }.start()
        val command = buildList {
            add(runtime.lldbServer.absolutePath)
            addAll(listOf(
                "gdbserver",
                "--native-regs",
                "--setsid",
                "--log-file=${hostServerLog.absolutePath}",
                "--log-channels=posix process ptrace",
                "127.0.0.1:$port",
                "--attach",
                inferiorPid.toString(),
            ))
        }
        hostServerProcess = ProcessBuilder(command)
            .directory(target.project)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = listOf(
                    runtime.lldbCore.parentFile?.absolutePath.orEmpty(),
                    appContext.applicationInfo.nativeLibraryDir,
                ).joinToString(":")
                environment()["HOME"] = appContext.filesDir.absolutePath
                environment()["TMPDIR"] = appContext.cacheDir.absolutePath
            }
            .start()
            .also { process ->
                Thread {
                    consumeProcessOutput(process, "[lldb-server] ", useErrorStream = false)
                }.apply { name = "FoldCode-LLDB-server-output"; isDaemon = true }.start()
            }
        return port
    }

    private fun startOpenOcd(target: DebugLaunchTarget.PicoSwd): Int {
        val port = 3333
        val command = listOf(
            runtime.openOcdLauncher.absolutePath,
            "-s", runtime.openOcdScripts.absolutePath,
            "-f", target.openOcdInterface,
            "-f", target.openOcdTarget,
            "-c", "gdb_port $port",
            "-c", "tcl_port disabled",
            "-c", "telnet_port disabled",
        )
        openOcdProcess = ProcessBuilder(command)
            .directory(target.project)
            .redirectErrorStream(true)
            .apply {
                environment()["FOLDCODE_OPENOCD_ROOT"] = runtime.openOcdExecutable.parentFile?.parentFile?.absolutePath.orEmpty()
                environment()["FOLDCODE_OPENOCD_EXECUTABLE"] = runtime.openOcdExecutable.absolutePath
            }
            .start()
            .also { process ->
                Thread {
                    consumeProcessOutput(process, "[openocd] ", useErrorStream = false)
                }.apply { name = "FoldCode-OpenOCD-output"; isDaemon = true }.start()
            }
        return port
    }

    /**
     * Process streams are closed from another thread when a debug session is
     * stopped or fails. Android reports that normal shutdown as an
     * InterruptedIOException, and an uncaught exception on a manually-created
     * thread terminates the app. Treat the closed pipe as end-of-stream.
     */
    private fun consumeProcessOutput(
        process: Process,
        prefix: String,
        useErrorStream: Boolean = true,
    ) {
        runCatching {
            val stream = if (useErrorStream) process.errorStream else process.inputStream
            stream.bufferedReader().useLines { lines ->
                lines.forEach {
                    if (!it.isLldbSymbolNoise()) appendConsole("$prefix$it\n")
                }
            }
        }.onFailure { error ->
            if (process.isAlive) appendConsole("$prefix${error.message ?: "output stream closed"}\n")
        }
    }

    private fun configureIfReady() {
        if (!initializedEventReceived || !launchResponseReceived) return
        applySourceBreakpoints {
            client?.request("configurationDone") {
                configurationDone = true
                if (!resumeHostStartupIfReady()) running("Debug target launched")
            }
        }
    }

    /**
     * Android host debugging attaches after the debug module is loaded so LLDB
     * can resolve source breakpoints immediately. Hide that implementation-only
     * SIGSTOP from the user, but do not resume until DAP configuration is done.
     */
    private fun resumeHostStartupIfReady(): Boolean {
        if (!configurationDone) return false
        val threadId = hostStartupThreadId ?: return false
        hostStartupThreadId = null
        applySourceBreakpoints {
            client?.request("continue", JSONObject().put("threadId", threadId))
            running("Running")
        }
        return true
    }

    private fun applySourceBreakpoints(onComplete: () -> Unit) {
        val grouped = requestedBreakpoints.groupBy(DebugBreakpoint::file)
        if (grouped.isEmpty()) {
            onComplete()
            return
        }
        var remaining = grouped.size
        val verified = mutableListOf<DebugBreakpoint>()
        grouped.forEach { (file, points) ->
            client?.request("setBreakpoints", JSONObject()
                .put("source", JSONObject()
                    .put("name", File(file).name)
                    .put("path", File(target!!.project, file).absolutePath))
                .put("breakpoints", JSONArray(points.map { JSONObject().put("line", it.line) }))) { response ->
                val returned = response.optJSONObject("body")?.optJSONArray("breakpoints") ?: JSONArray()
                points.forEachIndexed { index, point ->
                    val value = returned.optJSONObject(index)
                    verified += point.copy(
                        verified = value?.optBoolean("verified", false) == true,
                        message = value?.optString("message")?.takeIf(String::isNotBlank),
                    )
                }
                remaining--
                if (remaining == 0) {
                    publish(snapshot.copy(breakpoints = verified.sortedWith(compareBy(DebugBreakpoint::file, DebugBreakpoint::line))))
                    onComplete()
                }
            }
        }
    }

    private fun onEvent(event: String, body: JSONObject) {
        when (event) {
            "initialized" -> {
                initializedEventReceived = true
                configureIfReady()
            }
            "output" -> appendConsole(body.optString("output").withoutLldbSymbolNoise())
            "continued" -> running("Running")
            "stopped" -> {
                currentThreadId = body.optInt("threadId").takeIf { it > 0 }
                if (hostAwaitingExec) {
                    hostAwaitingExec = false
                    hostStartupThreadId = currentThreadId
                    resumeHostStartupIfReady()
                    return
                }
                publish(snapshot.copy(
                    status = DebugSessionStatus.Paused,
                    message = body.optString("description").ifBlank { body.optString("reason", "Paused") },
                    activeThreadId = currentThreadId,
                ))
                refreshStack()
            }
            "terminated", "exited" -> publish(snapshot.copy(
                status = DebugSessionStatus.Stopped,
                message = if (event == "exited") "Program exited with code ${body.optInt("exitCode")}" else "Debug session finished",
                activeFile = null,
                activeLine = null,
            ))
        }
    }

    private fun refreshStack() {
        val threadId = currentThreadId ?: return
        client?.request("stackTrace", JSONObject().put("threadId", threadId).put("startFrame", 0).put("levels", 40)) { response ->
            val array = response.optJSONObject("body")?.optJSONArray("stackFrames") ?: JSONArray()
            val frames = buildList {
                repeat(array.length()) { index ->
                    val frame = array.optJSONObject(index) ?: return@repeat
                    val sourcePath = frame.optJSONObject("source")?.optString("path")?.takeIf(String::isNotBlank)
                    add(DebugStackFrame(
                        id = frame.optInt("id"),
                        name = frame.optString("name", "frame $index"),
                        file = sourcePath,
                        line = frame.optInt("line", 1),
                        external = isExternalFrame(sourcePath),
                    ))
                }
            }
            val active = frames.firstOrNull()
            val enteredExternalCode = revealExternalFramesForPause && active?.external == true
            val visibleFrames = if ((target is DebugLaunchTarget.AndroidHost || target is DebugLaunchTarget.WebNode || target is DebugLaunchTarget.WebBrowser) && !enteredExternalCode) {
                frames.filterNot(DebugStackFrame::external)
            } else {
                frames
            }
            publish(snapshot.copy(
                frames = visibleFrames,
                activeFrameId = active?.id,
                activeFile = active?.file,
                activeLine = active?.line,
            ))
            active?.let { selectFrame(it.id) }
        }
    }

    fun selectFrame(frameId: Int) {
        publish(snapshot.copy(activeFrameId = frameId, variables = emptyList()))
        client?.request("scopes", JSONObject().put("frameId", frameId)) { response ->
            val scopes = response.optJSONObject("body")?.optJSONArray("scopes") ?: JSONArray()
            val references = buildList {
                repeat(scopes.length()) { index ->
                    val scope = scopes.optJSONObject(index) ?: return@repeat
                    val name = scope.optString("name")
                    val hint = scope.optString("presentationHint")
                    val userVariables = hint.equals("locals", true) || hint.equals("arguments", true) ||
                        name.contains("local", true) || name.contains("argument", true)
                    if (userVariables) {
                        scope.optInt("variablesReference").takeIf { it > 0 }?.let(::add)
                    }
                }
            }
            if (references.isEmpty()) return@request
            val values = mutableListOf<DebugVariable>()
            var remaining = references.size
            references.forEach { reference ->
                client?.request("variables", JSONObject().put("variablesReference", reference)) { variablesResponse ->
                    val array = variablesResponse.optJSONObject("body")?.optJSONArray("variables") ?: JSONArray()
                    repeat(array.length()) { index ->
                        val variable = array.optJSONObject(index) ?: return@repeat
                        val raw = parseDebugVariable(variable)
                        values += markUninitializedVariable(raw, frameId)
                    }
                    remaining--
                    if (remaining == 0) publish(snapshot.copy(variables = values.distinctBy(DebugVariable::name)))
                }
            }
        }
    }

    /** Lazily fetches children so large vectors and objects do not stall every pause. */
    fun toggleVariable(variablesReference: Int) {
        if (variablesReference <= 0 || snapshot.status != DebugSessionStatus.Paused) return
        val selected = findVariable(snapshot.variables, variablesReference) ?: return
        if (selected.childrenLoaded) {
            publish(snapshot.copy(variables = updateVariable(snapshot.variables, variablesReference) {
                it.copy(expanded = !it.expanded)
            }))
            return
        }
        client?.request("variables", JSONObject()
            .put("variablesReference", variablesReference)
            .put("start", 0)
            .put("count", MAX_DEBUG_CHILDREN)) { response ->
            if (!response.optBoolean("success", false)) {
                appendConsole("${response.optString("message", "Unable to inspect variable")}\n")
                return@request
            }
            val array = response.optJSONObject("body")?.optJSONArray("variables") ?: JSONArray()
            val children = buildList {
                repeat(array.length().coerceAtMost(MAX_DEBUG_CHILDREN)) { index ->
                    val child = array.optJSONObject(index) ?: return@repeat
                    val parsed = parseDebugVariable(child)
                    if (isUsefulChild(selected.type, parsed.name)) add(parsed)
                }
                if (array.length() > MAX_DEBUG_CHILDREN) {
                    add(DebugVariable("…", "first $MAX_DEBUG_CHILDREN items shown"))
                }
            }
            publish(snapshot.copy(variables = updateVariable(snapshot.variables, variablesReference) {
                it.copy(children = children, childrenLoaded = true, expanded = true)
            }))
        }
    }

    private fun parseDebugVariable(value: JSONObject): DebugVariable {
        val type = value.optString("type").takeIf(String::isNotBlank)
        val expandable = value.optInt("variablesReference")
            .takeUnless { isCompactDebuggerType(type) }
            ?: 0
        return DebugVariable(
            name = value.optString("name"),
            value = value.optString("value"),
            type = type,
            variablesReference = expandable,
        )
    }

    private fun isCompactDebuggerType(type: String?): Boolean {
        val normalized = type.orEmpty().replace(" ", "")
        return normalized == "std::string" ||
            normalized.contains("std::basic_string<") ||
            normalized == "std::string_view" ||
            normalized.contains("std::basic_string_view<")
    }

    private fun isUsefulChild(parentType: String?, name: String): Boolean {
        if (!parentType.orEmpty().contains("std::")) return true
        return !name.startsWith("__") &&
            !name.startsWith("_M_") &&
            name !in setOf("_M_impl", "_M_dataplus", "_M_string_length")
    }

    private fun findVariable(values: List<DebugVariable>, reference: Int): DebugVariable? {
        values.forEach { variable ->
            if (variable.variablesReference == reference) return variable
            findVariable(variable.children, reference)?.let { return it }
        }
        return null
    }

    private fun updateVariable(
        values: List<DebugVariable>,
        reference: Int,
        change: (DebugVariable) -> DebugVariable,
    ): List<DebugVariable> = values.map { variable ->
        when {
            variable.variablesReference == reference -> change(variable)
            variable.children.isNotEmpty() -> variable.copy(
                children = updateVariable(variable.children, reference, change),
            )
            else -> variable
        }
    }

    fun continueExecution() {
        revealExternalFramesForPause = false
        executionRequest("continue")
    }
    fun pause() = executionRequest("pause")
    fun stepOver() {
        revealExternalFramesForPause = false
        executionRequest("next", lineGranularity = true)
    }
    fun stepInto() {
        revealExternalFramesForPause = true
        executionRequest("stepIn", lineGranularity = true)
    }
    fun stepOut() {
        revealExternalFramesForPause = false
        executionRequest("stepOut", lineGranularity = true)
    }

    private fun isExternalFrame(sourcePath: String?): Boolean {
        if (target !is DebugLaunchTarget.AndroidHost && target !is DebugLaunchTarget.WebNode && target !is DebugLaunchTarget.WebBrowser) return false
        val path = sourcePath ?: return true
        val project = target?.project?.canonicalFile ?: return true
        val source = runCatching { File(path).canonicalFile }.getOrElse { File(path).absoluteFile }
        return source != project && !source.toPath().startsWith(project.toPath())
    }

    /**
     * LLDB can expose storage for a C/C++ local before its declaration has
     * executed. The bytes are not a value yet, so presenting them as a String
     * (or as a random integer) is misleading. Keep the variable visible but
     * describe its actual lifetime state until execution passes its declaration.
     */
    private fun markUninitializedVariable(variable: DebugVariable, frameId: Int): DebugVariable {
        if (target !is DebugLaunchTarget.AndroidHost) return variable
        val frame = snapshot.frames.firstOrNull { it.id == frameId } ?: return variable
        val source = frame.file?.let(::File)?.takeIf(File::isFile) ?: return variable
        val declarationLine = runCatching {
            val escaped = Regex.escape(variable.name)
            val declaration = Regex("\\b$escaped\\b\\s*(?:[=;,\\[])")
            source.useLines { lines ->
                lines.withIndex().firstOrNull { (_, text) ->
                    declaration.containsMatchIn(text.substringBefore("//"))
                }?.index?.plus(1)
            }
        }.getOrNull() ?: return variable
        return if (declarationLine >= frame.line) {
            variable.copy(value = "<not initialized>", variablesReference = 0)
        } else {
            variable
        }
    }

    private companion object {
        const val MAX_DEBUG_CHILDREN = 200
    }

    private fun executionRequest(
        command: String,
        lineGranularity: Boolean = false,
        granularity: String? = null,
    ) {
        val threadId = currentThreadId ?: snapshot.activeThreadId ?: return
        val arguments = JSONObject().put("threadId", threadId)
        if (command == "next" || command == "stepIn" || command == "stepOut") {
            arguments.put("singleThread", true)
        }
        if (granularity != null) {
            arguments.put("granularity", granularity)
        } else if (lineGranularity) {
            arguments.put("granularity", "line")
        }
        client?.request(command, arguments) { response ->
            if (!response.optBoolean("success", false)) appendConsole("${response.optString("message", "$command failed")}\n")
        }
    }

    /** Send interactive stdin to the Android program currently owned by LLDB. */
    fun sendInput(value: String): Boolean {
        if (snapshot.status != DebugSessionStatus.Running ||
            (target !is DebugLaunchTarget.AndroidHost && target !is DebugLaunchTarget.WebNode)) return false
        val process = hostInferiorProcess?.takeIf(Process::isAlive) ?: return false
        return runCatching {
            synchronized(inferiorInputLock) {
                process.outputStream.bufferedWriter().apply {
                    write(value)
                    newLine()
                    flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun evaluate(expression: String) {
        if (expression.isBlank()) return
        client?.request("evaluate", JSONObject()
            .put("expression", expression)
            .put("frameId", snapshot.activeFrameId ?: JSONObject.NULL)
            .put("context", "repl")) { response ->
            val result = response.optJSONObject("body")?.optString("result")
                ?: response.optString("message", "Evaluation failed")
            appendConsole("> $expression\n$result\n")
        }
    }

    fun restart() {
        client?.request("restart") { response ->
            if (!response.optBoolean("success", false)) appendConsole("${response.optString("message", "Restart is unsupported")}\n")
        }
    }

    fun stop() {
        client?.request("disconnect", JSONObject().put("terminateDebuggee", true))
        client?.close()
        client = null
        adapterProcess?.destroyForcibly()
        adapterProcess = null
        hostInferiorProcess?.destroyForcibly()
        hostInferiorProcess = null
        hostServerProcess?.destroyForcibly()
        hostServerProcess = null
        openOcdProcess?.destroyForcibly()
        openOcdProcess = null
        closeWebViewBridge()
        currentThreadId = null
        initializedEventReceived = false
        launchResponseReceived = false
        hostAwaitingExec = false
        configurationDone = false
        hostStartupThreadId = null
        revealExternalFramesForPause = false
        if (snapshot.status != DebugSessionStatus.Idle) publish(snapshot.copy(
            status = DebugSessionStatus.Stopped,
            message = "Debug session stopped",
            activeFile = null,
            activeLine = null,
        ))
    }

    private fun running(message: String) = publish(snapshot.copy(
        status = DebugSessionStatus.Running,
        message = message,
        frames = emptyList(),
        variables = emptyList(),
        activeFile = null,
        activeLine = null,
    ))

    private fun appendConsole(value: String) {
        publish(snapshot.copy(console = boundedWorkspaceOutput(snapshot.console + value)))
        main.post { outputListener(value) }
    }

    private fun readProtocolLine(stream: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = stream.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun String.isLldbSymbolNoise(): Boolean =
        contains("No LZMA support found for reading .gnu_debugdata section")

    private fun String.withoutLldbSymbolNoise(): String =
        lineSequence()
            .filterNot { it.isLldbSymbolNoise() }
            .joinToString("\n")
            .let { filtered ->
                if (endsWith('\n') && filtered.isNotEmpty()) "$filtered\n" else filtered
            }

    private fun JSONObject.failureMessage(fallback: String): String {
        val direct = optString("message").takeIf(String::isNotBlank)
        val body = optJSONObject("body")
        val structured = body?.optJSONObject("error")?.let { error ->
            error.optString("format").takeIf(String::isNotBlank)
                ?: error.optString("message").takeIf(String::isNotBlank)
        }
        return direct ?: structured ?: body?.optString("message")?.takeIf(String::isNotBlank) ?: fallback
    }

    private fun fail(message: String) {
        publish(snapshot.copy(status = DebugSessionStatus.Failed, message = message))
        client?.close()
        client = null
        adapterProcess?.destroyForcibly()
        adapterProcess = null
        hostInferiorProcess?.destroyForcibly()
        hostInferiorProcess = null
        hostServerProcess?.destroyForcibly()
        hostServerProcess = null
        openOcdProcess?.destroyForcibly()
        openOcdProcess = null
        closeWebViewBridge()
        hostAwaitingExec = false
        configurationDone = false
        hostStartupThreadId = null
        revealExternalFramesForPause = false
    }

    private fun closeWebViewBridge() {
        webViewBridge?.close()
        webViewBridge = null
        setWebViewDebuggingEnabled(BuildConfig.DEBUG)
    }

    /** WebView's CDP socket is global, so expose it only for an explicit IDE session. */
    private fun setWebViewDebuggingEnabled(enabled: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            WebView.setWebContentsDebuggingEnabled(enabled)
            return
        }
        val applied = CountDownLatch(1)
        main.post {
            WebView.setWebContentsDebuggingEnabled(enabled)
            applied.countDown()
        }
        applied.await(2, TimeUnit.SECONDS)
    }

    private fun publish(value: DebugSessionSnapshot) {
        snapshot = value
        main.post { listener(value) }
    }

    override fun close() = stop()
}
