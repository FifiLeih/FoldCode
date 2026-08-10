package dev.foldcode.ide

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Owns independent project shells without inflating the workspace composable. */
internal class WorkspaceTerminalController(context: Context) {
    private data class Session(
        val id: Int,
        val engine: ProjectTerminal,
        val transcript: String = "",
        val commandRunning: Boolean = false,
        val webServerOwner: Boolean = false,
        val workingDirectory: File,
    )

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var sessions = emptyList<Session>()
    private var projectDirectory: File? = null
    private var nextId = 1
    private var webServerRunning = false

    var sessionInfos by mutableStateOf<List<TerminalSessionInfo>>(emptyList())
        private set
    var selectedId by mutableIntStateOf(0)
        private set
    var activePrompt by mutableStateOf("workspace")
        private set

    var onActiveTranscriptChanged: (String) -> Unit = {}
    var onActiveRunningChanged: (Boolean) -> Unit = {}
    var onWebServerRunningChanged: (Boolean) -> Unit = {}

    fun startProject(directory: File) {
        closeAll()
        projectDirectory = directory
        create("")
    }

    fun create(currentTranscript: String) {
        val directory = projectDirectory
        if (directory == null || !directory.isDirectory) {
            onActiveTranscriptChanged("Open a project to start a terminal")
            return
        }
        syncActive(currentTranscript)
        val id = nextId++
        val engine = ProjectTerminal(applicationContext)
        sessions = sessions + Session(id, engine, workingDirectory = directory)
        selectedId = id
        activePrompt = terminalPrompt(directory, directory)
        publish()
        onActiveTranscriptChanged("")
        onActiveRunningChanged(false)
        Thread({ startEngine(id, engine, directory, directory) }, "FoldCode-terminal-$id").start()
    }

    fun select(id: Int, currentTranscript: String) {
        if (id == selectedId) return
        syncActive(currentTranscript)
        val selected = sessions.firstOrNull { it.id == id } ?: return
        selectedId = id
        activePrompt = terminalPrompt(projectDirectory, selected.workingDirectory)
        onActiveTranscriptChanged(selected.transcript)
        onActiveRunningChanged(selected.commandRunning)
    }

    fun delete(id: Int, currentTranscript: String) {
        val deleted = sessions.firstOrNull { it.id == id } ?: return
        if (id == selectedId) syncActive(currentTranscript)
        deleted.engine.close()
        sessions = sessions.filterNot { it.id == id }
        if (selectedId == id) {
            val replacement = sessions.lastOrNull()
            selectedId = replacement?.id ?: 0
            activePrompt = terminalPrompt(projectDirectory, replacement?.workingDirectory)
            onActiveTranscriptChanged(replacement?.transcript.orEmpty())
            onActiveRunningChanged(replacement?.commandRunning == true)
        }
        publish()
    }

    fun activeEngine(): ProjectTerminal? = sessions.firstOrNull { it.id == selectedId }?.engine

    fun engine(sessionId: Int): ProjectTerminal? = sessions.firstOrNull { it.id == sessionId }?.engine

    fun activeCommandRunning(): Boolean = sessions.firstOrNull { it.id == selectedId }?.commandRunning == true

    fun webServerSessionId(): Int? = sessions.firstOrNull(Session::webServerOwner)?.id

    fun isRunningWebServer(sessionId: Int): Boolean = sessions.any {
        it.id == sessionId && it.webServerOwner && it.commandRunning
    }

    /** Atomically reserves the selected idle shell as this project's only web server owner. */
    fun reserveActiveWebServer(currentTranscript: String): Int? {
        if (webServerSessionId() != null) return null
        syncActive(currentTranscript)
        val active = sessions.firstOrNull { it.id == selectedId && !it.commandRunning } ?: return null
        sessions = sessions.map { session ->
            if (session.id == active.id) session.copy(webServerOwner = true) else session
        }
        publish()
        return active.id
    }

    fun releaseWebServer(sessionId: Int) {
        var changed = false
        sessions = sessions.map { session ->
            if (session.id == sessionId && session.webServerOwner) {
                changed = true
                session.copy(webServerOwner = false)
            } else session
        }
        if (changed) publish()
    }

    fun replaceSessionTranscript(sessionId: Int, transcript: String, commandRunning: Boolean) {
        var found = false
        sessions = sessions.map { session ->
            if (session.id == sessionId) {
                found = true
                session.copy(transcript = transcript, commandRunning = commandRunning)
            } else session
        }
        if (!found) return
        publish()
        if (selectedId == sessionId) {
            onActiveTranscriptChanged(transcript)
            onActiveRunningChanged(commandRunning)
        }
    }

    fun appendSessionTranscript(sessionId: Int, output: String) {
        var transcript: String? = null
        sessions = sessions.map { session ->
            if (session.id == sessionId) {
                session.copy(transcript = appendWorkspaceOutput(session.transcript, output).also { transcript = it })
            } else session
        }
        if (selectedId == sessionId) transcript?.let(onActiveTranscriptChanged)
    }

    /**
     * Attach a compiler-managed process to the selected terminal. The project
     * shell itself remains idle; FoldCode owns the process and streams its
     * output into this terminal session until [finishManagedCommand] is called.
     */
    fun beginManagedCommand(currentTranscript: String, command: String): Int? {
        val id = selectedId.takeIf { selected -> sessions.any { it.id == selected } } ?: return null
        syncActive(currentTranscript)
        val nextTranscript = appendTranscript(currentTranscript, command)
        sessions = sessions.map { session ->
            if (session.id == id) session.copy(transcript = nextTranscript, commandRunning = true)
            else session
        }
        publish()
        onActiveTranscriptChanged(nextTranscript)
        onActiveRunningChanged(true)
        return id
    }

    fun appendManagedOutput(id: Int, output: String) {
        if (output.isEmpty()) return
        var nextTranscript: String? = null
        sessions = sessions.map { session ->
            if (session.id != id) session
            else session.copy(
                transcript = appendWorkspaceOutput(session.transcript, output).also { nextTranscript = it },
            )
        }
        if (selectedId == id) nextTranscript?.let(onActiveTranscriptChanged)
    }

    fun finishManagedCommand(id: Int, finalOutput: String? = null) {
        var nextTranscript: String? = null
        var found = false
        sessions = sessions.map { session ->
            if (session.id != id) session
            else {
                found = true
                val transcript = finalOutput
                    ?.takeIf(String::isNotBlank)
                    ?.let { appendTranscript(session.transcript, it) }
                    ?: session.transcript
                nextTranscript = transcript
                session.copy(transcript = transcript, commandRunning = false)
            }
        }
        if (!found) return
        publish()
        if (selectedId == id) {
            nextTranscript?.let(onActiveTranscriptChanged)
            onActiveRunningChanged(false)
        }
    }

    fun interruptManagedCommand(id: Int): Boolean {
        val session = sessions.firstOrNull { it.id == id && it.commandRunning } ?: return false
        val stoppedTranscript = appendTranscript(session.transcript, "^C")
        sessions = sessions.map { current ->
            if (current.id == id) current.copy(transcript = stoppedTranscript, commandRunning = false)
            else current
        }
        publish()
        if (selectedId == id) {
            onActiveTranscriptChanged(stoppedTranscript)
            onActiveRunningChanged(false)
        }
        return true
    }

    /**
     * Stop the foreground command without deleting the terminal tab. Android's
     * pipe-backed shell has no PTY, so writing the Ctrl+C byte would only send
     * input to Node instead of generating SIGINT. Replacing the project shell
     * reliably terminates its complete child tree and leaves a fresh prompt in
     * the same terminal session.
     */
    fun interruptActive(currentTranscript: String): Boolean {
        val active = sessions.firstOrNull { it.id == selectedId } ?: return false
        if (!active.commandRunning) return false
        return interruptSession(active, currentTranscript)
    }

    /** Stops the owned web server even when the user is viewing another terminal tab. */
    fun interruptWebServer(currentTranscript: String): Boolean {
        val owner = sessions.firstOrNull(Session::webServerOwner) ?: return false
        val transcript = if (owner.id == selectedId) currentTranscript else owner.transcript
        return interruptSession(owner, transcript)
    }

    private fun interruptSession(active: Session, currentTranscript: String): Boolean {
        val directory = projectDirectory ?: return false
        if (!active.commandRunning) return false
        val replacement = ProjectTerminal(applicationContext)
        active.engine.close()
        val stoppedTranscript = currentTranscript.trimEnd() + "\n^C\n"
        sessions = sessions.map { session ->
            if (session.id == active.id) {
                session.copy(
                    engine = replacement,
                    transcript = stoppedTranscript,
                    commandRunning = false,
                    webServerOwner = false,
                    workingDirectory = active.workingDirectory,
                )
            } else session
        }
        publish()
        if (selectedId == active.id) {
            onActiveTranscriptChanged(stoppedTranscript)
            onActiveRunningChanged(false)
            activePrompt = terminalPrompt(directory, active.workingDirectory)
        }
        Thread(
            { startEngine(active.id, replacement, directory, active.workingDirectory) },
            "FoldCode-terminal-${active.id}-restart",
        ).start()
        return true
    }

    fun syncActive(transcript: String, commandRunning: Boolean? = null) {
        val id = selectedId
        if (id == 0) return
        sessions = sessions.map { session ->
            if (session.id != id) session
            else session.copy(
                transcript = transcript,
                commandRunning = commandRunning ?: session.commandRunning,
            )
        }
        publish()
    }

    fun closeAll() {
        sessions.forEach { it.engine.close() }
        sessions = emptyList()
        selectedId = 0
        projectDirectory = null
        activePrompt = "workspace"
        publish()
    }

    private fun startEngine(
        id: Int,
        engine: ProjectTerminal,
        projectDirectory: File,
        initialWorkingDirectory: File,
    ) {
        engine.start(
            projectDirectory,
            initialWorkingDirectory,
            onOutput = { chunk ->
                mainHandler.post {
                    sessions = sessions.map { session ->
                        if (session.id == id && session.engine === engine) {
                            session.copy(transcript = appendWorkspaceOutput(session.transcript, chunk))
                        } else session
                    }
                    if (selectedId == id) {
                        val transcript = sessions.firstOrNull { it.id == id }?.transcript.orEmpty()
                        onActiveTranscriptChanged(transcript)
                    }
                }
            },
            onCommandFinished = { status, workingDirectory ->
                mainHandler.post {
                    var commandBelongsToCurrentEngine = false
                    var nextTranscript: String? = null
                    sessions = sessions.map { session ->
                        if (session.id == id && session.engine === engine) {
                            commandBelongsToCurrentEngine = true
                            val exitNotice = terminalProcessExitNotice(status, session.webServerOwner)
                            val transcript = exitNotice?.let {
                                appendWorkspaceOutput(session.transcript, "\n$it\n")
                            } ?: session.transcript
                            nextTranscript = transcript
                            session.copy(
                                transcript = transcript,
                                commandRunning = false,
                                webServerOwner = false,
                                workingDirectory = workingDirectory,
                            )
                        } else session
                    }
                    publish()
                    if (commandBelongsToCurrentEngine && selectedId == id) {
                        activePrompt = terminalPrompt(projectDirectory, workingDirectory)
                        nextTranscript?.let(onActiveTranscriptChanged)
                        onActiveRunningChanged(false)
                    }
                }
            },
            onExit = {},
        )
    }

    private fun publish() {
        sessionInfos = sessions.map { TerminalSessionInfo(it.id, it.commandRunning, it.webServerOwner) }
        val nextWebServerRunning = sessions.any(Session::webServerOwner)
        if (nextWebServerRunning != webServerRunning) {
            webServerRunning = nextWebServerRunning
            onWebServerRunningChanged(nextWebServerRunning)
        }
    }

    private fun appendTranscript(transcript: String, value: String): String = buildString {
        if (transcript.isNotBlank()) append(transcript.trimEnd()).append('\n')
        append(value.trimStart())
    }
}

internal fun terminalProcessExitNotice(status: Int, webServerOwner: Boolean): String? = when {
    webServerOwner && status == 0 -> "Web server stopped."
    webServerOwner -> "Web server exited with status $status. Check the output above; port 3000 may already be in use."
    status != 0 -> "Command exited with status $status."
    else -> null
}

internal fun terminalPrompt(projectDirectory: File?, workingDirectory: File?): String {
    val root = projectDirectory?.let { runCatching { it.canonicalFile }.getOrNull() }
    val current = workingDirectory?.let { runCatching { it.canonicalFile }.getOrNull() }
    if (root == null) return current?.absolutePath ?: "workspace"
    if (current == null) return root.name.ifBlank { "workspace" }
    val relative = runCatching {
        val rootPath = root.toPath()
        val currentPath = current.toPath()
        if (currentPath.startsWith(rootPath)) rootPath.relativize(currentPath).toString() else null
    }.getOrNull()
    return when {
        relative == null -> current.absolutePath
        relative.isBlank() -> root.name.ifBlank { "workspace" }
        else -> "${root.name.ifBlank { "workspace" }}/${relative.replace(File.separatorChar, '/')}"
    }
}
