package dev.foldcode.ide

import android.content.Context
import androidx.compose.ui.focus.FocusManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
internal class WorkspaceBuildBindings(
    val terminal: MutableWorkspaceValue<String>,
    val terminalBeforeEditing: MutableWorkspaceValue<Boolean?>,
    val terminalVisible: MutableWorkspaceValue<Boolean>,
    val destination: MutableWorkspaceValue<ActivityDestination>,
    val sidePanelVisible: MutableWorkspaceValue<Boolean>,
    val drawerOpen: MutableWorkspaceValue<Boolean>,
    val building: MutableWorkspaceValue<Boolean>,
    val buildOutput: MutableWorkspaceValue<String>,
    val diagnosticsOutput: MutableWorkspaceValue<String>,
    val projectTree: MutableWorkspaceValue<List<ProjectTreeEntry>>,
    val managedTerminalSessionId: MutableWorkspaceValue<Int?>,
    val consoleActive: MutableWorkspaceValue<Boolean>,
    val terminalCommandRunning: MutableWorkspaceValue<Boolean>,
    val picotoolBusy: MutableWorkspaceValue<Boolean>,
    val files: MutableWorkspaceValue<Map<String, ProjectFile>>,
    val savedProjectSources: MutableWorkspaceValue<Map<String, String>>,
    val picoCMakeTargets: MutableWorkspaceValue<List<String>>,
    val picoConfigurationRevision: MutableWorkspaceValue<Int>,
    val cleaningPicoBuild: MutableWorkspaceValue<Boolean>,
    val projectSources: () -> Map<String, String>,
    val projectKey: () -> String,
    val projectName: () -> String,
    val folders: () -> Set<String>,
    val activeFileName: () -> String,
    val appearance: () -> IdePreferences,
    val debugSnapshot: () -> DebugSessionSnapshot,
    val currentPicoConfiguration: () -> PicoProjectConfiguration,
    val openPicoExtensionDetails: () -> Unit,
    val openGnuLanguagesExtensionDetails: () -> Unit,
    val openWebExtensionDetails: () -> Unit,
    val openWebPreview: () -> Unit,
)

internal class WorkspaceBuildController(
    private val context: Context,
    private val focusManager: FocusManager,
    private val engine: CompilerEngine,
    private val rustPicoCompiler: RustPicoCompilerEngine,
    private val rustIntelligence: RustExtensionIntelligence,
    private val gnuLanguageIntelligence: GnuLanguageIntelligence,
    private val gnuLanguagesCompiler: GnuLanguagesCompilerEngine,
    private val picoUsbFlasher: PicoUsbFlasher,
    private val microPythonUsbRunner: MicroPythonUsbRunner,
    private val picotoolRunner: PicotoolCommandRunner,
    private val picoRuntimeDelivery: PicoRuntimeDelivery,
    private val gitEngine: GitEngine,
    private val debugController: DebugSessionController,
    private val picoConfigurationStore: PicoProjectConfigurationStore,
    private val cmakeDependencyResolver: CMakeDependencyResolver,
    private val terminalController: WorkspaceTerminalController,
    private val webProjectRuntime: WebProjectRuntime,
    private val extensions: WorkspaceExtensionController,
    private val bindings: WorkspaceBuildBindings,
) {
    private var terminal by bindings.terminal
    private var terminalBeforeEditing by bindings.terminalBeforeEditing
    private var terminalVisible by bindings.terminalVisible
    private var destination by bindings.destination
    private var sidePanelVisible by bindings.sidePanelVisible
    private var drawerOpen by bindings.drawerOpen
    private var building by bindings.building
    private var buildOutput by bindings.buildOutput
    private var diagnosticsOutput by bindings.diagnosticsOutput
    private var projectTree by bindings.projectTree
    private var managedTerminalSessionId by bindings.managedTerminalSessionId
    private var consoleActive by bindings.consoleActive
    private var terminalCommandRunning by bindings.terminalCommandRunning
    private var picotoolBusy by bindings.picotoolBusy
    private var files by bindings.files
    private var savedProjectSources by bindings.savedProjectSources
    private var picoCMakeTargets by bindings.picoCMakeTargets
    private var picoConfigurationRevision by bindings.picoConfigurationRevision
    private var cleaningPicoBuild by bindings.cleaningPicoBuild

    private val projectSources get() = bindings.projectSources()
    private val projectKey get() = bindings.projectKey()
    private val projectName get() = bindings.projectName()
    private val folders get() = bindings.folders()
    private val activeFileName get() = bindings.activeFileName()
    private val appearance get() = bindings.appearance()
    private val debugSnapshot get() = bindings.debugSnapshot()
    private val cppExtensionInfo get() = extensions.cppExtensionInfo
    private val picoExtensionInfo get() = extensions.picoExtensionInfo
    private val pythonExtensionInfo get() = extensions.pythonExtensionInfo
    private val rustExtensionInfo get() = extensions.rustExtensionInfo
    private val gnuLanguagesExtensionInfo get() = extensions.gnuLanguagesExtensionInfo
    private val webExtensionInfo get() = extensions.webExtensionInfo
    private val gitToolsExtensionInfo get() = extensions.gitToolsExtensionInfo
    private val gnuArmExtensionInfo get() = extensions.gnuArmExtensionInfo

    private fun currentPicoConfiguration() = bindings.currentPicoConfiguration()
    private fun openPicoExtensionDetails() = bindings.openPicoExtensionDetails()
    private fun openGnuLanguagesExtensionDetails() = bindings.openGnuLanguagesExtensionDetails()
    private fun openWebExtensionDetails() = bindings.openWebExtensionDetails()
    private fun openWebPreview() = bindings.openWebPreview()

    /**
     * Cargo creates Cargo.lock during the first build. The editor's initial file
     * snapshot predates that generated file, so include the on-disk lockfile in
     * the next save instead of treating it as a removed editor buffer.
     */
    private fun rustSourcesForSave(projectDirectory: File): Map<String, String> {
        return rustProjectSourcesForSave(projectDirectory, projectSources)
    }

    /** Make a newly generated lockfile part of the live/saved editor model. */
    private fun registerGeneratedCargoLock(projectDirectory: File) {
        val lock = File(projectDirectory, "Cargo.lock")
        val content = lock.takeIf(File::isFile)?.runCatching(File::readText)?.getOrNull() ?: return
        val current = files["Cargo.lock"]
        if (current?.content != content || current?.readOnly == true) {
            files = files + ("Cargo.lock" to ProjectFile("Cargo.lock", content))
        }
        if (savedProjectSources["Cargo.lock"] != content) {
            savedProjectSources = savedProjectSources + ("Cargo.lock" to content)
        }
    }

    private fun focusRunningWebServer(sessionId: Int, message: String) {
        terminalVisible = true
        terminalController.select(sessionId, terminal)
        terminal = appendWorkspaceOutput(terminal, "\n$message\n")
        terminalController.syncActive(terminal)
    }

    private fun cleanStaleWebServers() {
        val staleServers = ManagedWebServerProcesses.terminateStale(context.filesDir)
        if (staleServers == 0) return
        terminal = appendWorkspaceOutput(
            terminal,
            "\nStopped $staleServers stale Web server process${if (staleServers == 1) "" else "es"}.\n",
        )
        terminalController.syncActive(terminal)
    }

    fun promptedCommand(command: String): String =
        "${terminalController.activePrompt.ifBlank { projectName.ifBlank { "workspace" } }} \$ $command"

    fun runCurrentProject(debug: Boolean = false, forcePicoBuild: Boolean = false) {
        if (building || cleaningPicoBuild) return
        // Running starts a terminal session, not another editor interaction.
        // Explicitly end any retained editor focus session so inserting the
        // console input field cannot restore the IME and hide its own panel.
        focusManager.clearFocus(force = true)
        terminalBeforeEditing = null
        terminalVisible = true
        val detectedPicoProject = isPicoSdkProject(projectSources)
        val microPythonProject = isPicoMicroPythonProject(projectSources)
        val rustPicoProject = isPicoRustProject(projectSources)
        val rustCargoProject = isRustCargoProject(projectSources) && !rustPicoProject
        val gnuLanguageProject = gnuLanguagesCompiler.detect(projectSources)
        val cppSourcesPresent = projectSources.keys.any {
            it.endsWith(".cpp", true) || it.endsWith(".cc", true) || it.endsWith(".cxx", true)
        }
        val activePythonEntry = activeFileName.takeIf { it.endsWith(".py", true) && it in projectSources }
        val fallbackPythonEntry = projectSources.keys.filter { it.endsWith(".py", true) }.sorted().firstOrNull()
        val pythonEntry = activePythonEntry ?: fallbackPythonEntry
        val pythonProject = !forcePicoBuild && !microPythonProject && pythonEntry != null &&
            (activePythonEntry != null || (!detectedPicoProject && !cppSourcesPresent))
        val activeWebEntry = activeFileName.takeIf {
            it in projectSources && listOf(".js", ".mjs", ".cjs", ".ts", ".tsx").any { suffix -> it.endsWith(suffix, true) }
        }
        val webProject = !forcePicoBuild && !detectedPicoProject &&
            ("package.json" in projectSources || activeWebEntry != null)
        val picoProject = detectedPicoProject && !pythonProject
        if (webProject) {
            if (webExtensionInfo == null) {
                terminal = "Install the Web Development extension before running this project"
                openWebExtensionDetails()
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            val browserProject = "package.json" in projectSources
            val webServerSessionId = if (browserProject) {
                terminalController.webServerSessionId()?.let { existing ->
                    focusRunningWebServer(existing, "This project already has a running web server. Stop it before starting another.")
                    return
                }
                cleanStaleWebServers()
                if (terminalController.activeCommandRunning()) {
                    terminal = appendWorkspaceOutput(terminal, "\nCurrent terminal is busy. Select an idle terminal before running the web project.\n")
                    terminalController.syncActive(terminal)
                    return
                }
                terminalController.reserveActiveWebServer(terminal) ?: run {
                    terminal = appendWorkspaceOutput(terminal, "\nCould not reserve this terminal for the web server.\n")
                    terminalController.syncActive(terminal)
                    return
                }
            } else null
            Thread({
                runCatching { gitEngine.saveProject(projectKey, projectSources, folders) }
                val command = if (browserProject) {
                    runCatching {
                        webProjectRuntime.prepare(projectKey, gitEngine.projectDirectoryFile(projectKey)).startCommand()
                    }.getOrElse { error ->
                        postToMainThread {
                            requireNotNull(webServerSessionId).let(terminalController::releaseWebServer)
                            terminalController.replaceSessionTranscript(
                                requireNotNull(webServerSessionId),
                                "Could not prepare Web project: ${error.message ?: error.javaClass.simpleName}",
                                commandRunning = false,
                            )
                        }
                        return@Thread
                    }
                } else {
                    val entry = activeWebEntry ?: return@Thread
                    val inspector = if (debug) "--inspect=127.0.0.1:9229 " else ""
                    "node $inspector'${entry.replace("'", "'\\''")}'"
                }
                val commandDispatched = AtomicBoolean(false)
                val commandDispatchFinished = CountDownLatch(1)
                postToMainThread {
                    val shell = webServerSessionId?.let(terminalController::engine)
                        ?: terminalController.activeEngine()
                    if (shell?.sendCommand(command) == true) {
                        commandDispatched.set(true)
                        if (webServerSessionId != null) {
                            terminalController.replaceSessionTranscript(
                                webServerSessionId,
                                "Starting Web project…\n",
                                commandRunning = true,
                            )
                        } else {
                            terminalCommandRunning = true
                            terminal = "${projectName.ifBlank { "workspace" }} \$ node ${activeWebEntry.orEmpty()}\n"
                            terminalController.syncActive(terminal, commandRunning = true)
                        }
                    } else {
                        if (webServerSessionId != null) {
                            terminalController.releaseWebServer(webServerSessionId)
                            terminalController.replaceSessionTranscript(
                                webServerSessionId,
                                "Terminal process is unavailable. Reopen the project and try again.",
                                commandRunning = false,
                            )
                        } else {
                            terminal = "Terminal process is unavailable. Reopen the project and try again."
                        }
                    }
                    commandDispatchFinished.countDown()
                }
                commandDispatchFinished.await(2, TimeUnit.SECONDS)
                if (commandDispatched.get() && browserProject) {
                    val ready = webProjectRuntime.waitUntilReady("http://127.0.0.1:3000")
                    if (ready) Thread.sleep(WEB_SERVER_START_STABILITY_MILLIS)
                    if (ready) {
                        postToMainThread {
                            if (terminalController.isRunningWebServer(requireNotNull(webServerSessionId))) {
                                openWebPreview()
                            } else {
                                terminalController.appendSessionTranscript(
                                    requireNotNull(webServerSessionId),
                                    "\nThe Web server exited during startup. Preview was not opened.\n",
                                )
                            }
                        }
                    } else {
                        postToMainThread {
                            terminalController.appendSessionTranscript(
                                requireNotNull(webServerSessionId),
                                "\nWeb server did not become ready at http://127.0.0.1:3000\n",
                            )
                        }
                    }
                }
            }, "FoldCode-Web-run").start()
            return
        }
        if (microPythonProject) {
            if (pythonExtensionInfo == null) {
                terminal = "Install the Python extension for MicroPython editing and completion"
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            if (picoExtensionInfo == null) {
                terminal = "Install the Raspberry Pi Pico extension before deploying MicroPython"
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            val board = picoBoard(projectSources)
            val firmware = File(context.filesDir, "micropython-firmware/${board.bundleId}/firmware.uf2")
            if (!microPythonFirmwareReady(requireNotNull(picoExtensionInfo), firmware.isFile && firmware.length() > 0L)) {
                terminal = "Install the MicroPython · ${board.displayName} component before deploying to the board"
                openPicoExtensionDetails()
                destination = ActivityDestination.Extensions
                return
            }
            building = true
            terminal = "MicroPython deploy · preparing ${board.displayName}…"
            buildOutput = terminal
            microPythonUsbRunner.deploy(
                files = projectSources,
                firmware = firmware,
                status = { progress ->
                    terminal = "MicroPython deploy · $progress"
                    buildOutput = progress
                },
                finished = { result ->
                    building = false
                    terminal = result.fold(
                        onSuccess = { it },
                        onFailure = { "MicroPython deploy failed: ${it.message ?: it.javaClass.simpleName}" },
                    )
                    buildOutput = terminal
                },
            )
            return
        }
        if (rustPicoProject) {
            if (rustExtensionInfo == null) {
                terminal = "Install the Rust extension before building this project"
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            val board = picoBoard(projectSources)
            val rustDebugBuild = debug || currentPicoConfiguration().buildType == PicoBuildType.Debug
            // rust-analyzer runs its own Cargo flycheck. Two glibc Rust
            // toolchains under PRoot can exhaust Android's available memory
            // even though each Cargo invocation is individually limited to -j1.
            rustIntelligence.reset()
            building = true
            val cargoBuildCommand = if (rustDebugBuild) "cargo build" else "cargo build --release"
            terminal = "${promptedCommand(cargoBuildCommand)}\nBuilding ${board.displayName} ${if (rustDebugBuild) "debug " else ""}firmware…"
            buildOutput = terminal
            Thread({
                val projectDirectory = gitEngine.projectDirectoryFile(projectKey)
                val result = runCatching {
                    gitEngine.saveProject(projectKey, rustSourcesForSave(projectDirectory), folders)
                    rustPicoCompiler.build(projectDirectory, board, rustDebugBuild) { progress ->
                        postToMainThread {
                            if (building) {
                                terminal = "${promptedCommand(cargoBuildCommand)}\n$progress"
                                buildOutput = progress
                            }
                        }
                    }
                }.getOrElse { error -> PicoBuildResult(false, "Rust build failed: ${error.message}") }
                postToMainThread {
                    registerGeneratedCargoLock(projectDirectory)
                    building = false
                    terminal = result.output
                    buildOutput = result.output
                    if (!result.succeeded) diagnosticsOutput = result.output
                    projectTree = gitEngine.projectTree(projectKey)
                }
            }, "FoldCode-Rust-build").start()
            return
        }
        if (rustCargoProject) {
            if (rustExtensionInfo == null) {
                terminal = "Install the Rust extension before building this project"
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            rustIntelligence.reset()
            val prompt = projectName.ifBlank { "workspace" }
            val rustCommand = if (debug) "$prompt $ cargo run" else "$prompt $ cargo run --release"
            val rustProgramStarted = AtomicBoolean(false)
            val rustManagedSessionId = terminalController.beginManagedCommand(
                terminal,
                "$rustCommand\nBuilding Rust project…\n",
            )
            managedTerminalSessionId = rustManagedSessionId
            terminalBeforeEditing = null
            terminalVisible = true
            building = true
            buildOutput = "Building Rust project…"
            if (rustManagedSessionId == null) terminal = "$rustCommand\nBuilding Rust project…"
            Thread({
                val projectDirectory = gitEngine.projectDirectoryFile(projectKey)
                val result = runCatching {
                    gitEngine.saveProject(projectKey, rustSourcesForSave(projectDirectory), folders)
                    rustPicoCompiler.runProject(
                        projectDirectory,
                        debug = debug,
                        onProgramStarted = {
                            postToMainThread {
                                terminalBeforeEditing = null
                                terminalVisible = true
                                consoleActive = true
                                rustProgramStarted.set(true)
                            }
                        },
                        onProgramOutput = { chunk ->
                            postToMainThread {
                                rustManagedSessionId?.let { terminalController.appendManagedOutput(it, chunk) }
                                    ?: run { terminal += chunk }
                            }
                        },
                    )
                }.getOrElse { error -> BuildResult(false, "Rust build failed: ${error.message}") }
                postToMainThread {
                    registerGeneratedCargoLock(projectDirectory)
                    consoleActive = false
                    building = false
                    val finalTerminalOutput = when {
                        result.wasCancelledByUser() || result.succeeded -> null
                        rustProgramStarted.get() -> result.output.lineSequence()
                            .map(String::trim)
                            .lastOrNull { it.startsWith("Process exited with") }
                        else -> result.output.trimEnd()
                    }
                    if (rustManagedSessionId != null) {
                        terminalController.finishManagedCommand(rustManagedSessionId, finalTerminalOutput)
                    } else if (finalTerminalOutput != null) {
                        terminal = finalTerminalOutput
                    }
                    if (managedTerminalSessionId == rustManagedSessionId) managedTerminalSessionId = null
                    buildOutput = result.output
                    if (!result.succeeded && !result.wasCancelledByUser()) diagnosticsOutput = result.output
                    projectTree = gitEngine.projectTree(projectKey)
                }
            }, "FoldCode-Rust-run").start()
            return
        }
        if (pythonProject && pythonExtensionInfo == null) {
            terminal = "Install the Python extension before running Python scripts"
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            drawerOpen = true
            return
        }
        if (gnuLanguageProject != null) {
            if (gnuLanguagesExtensionInfo == null) {
                terminal = "Install the ${gnuLanguageProject.displayName} extension before compiling this project"
                openGnuLanguagesExtensionDetails()
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            // A live syntax-check uses the same PRoot/GCC bridge as Run.
            // Stop it before compiling so two guest compiler trees cannot race
            // while launching GCC's internal cc1/f951 subprocesses.
            gnuLanguageIntelligence.reset()
        }
        if (!pythonProject && !rustCargoProject && gnuLanguageProject == null && cppExtensionInfo == null) {
            terminal = if (picoProject) {
                "Install the C/C++ dependency required by the Raspberry Pi Pico extension"
            } else "Install the C/C++ extension before compiling this project"
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            drawerOpen = true
            return
        }
        if (picoProject && picoExtensionInfo == null) {
            terminal = "Install the Raspberry Pi Pico extension before building this project"
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            drawerOpen = true
            return
        }
        val picoConfiguration = if (picoProject) currentPicoConfiguration() else null
        if (picoConfiguration != null && picoExtensionInfo?.components
                ?.none { it.id == picoConfiguration.board.bundleId && it.installed } != false
        ) {
            terminal = "Install the ${picoConfiguration.board.displayName} component before building this project"
            openPicoExtensionDetails()
            destination = ActivityDestination.Extensions
            return
        }
        if (picoProject && picoRuntimeDelivery.runtimeOrNull() == null) {
            terminal = "The native Full Pico CMake runtime is unavailable in this FoldCode build."
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            drawerOpen = true
            return
        }
        val requirementReport = if (picoProject && picoConfiguration != null && projectKey.startsWith("project:")) {
            runCatching {
                PicoProjectRequirementScanner.scan(
                    gitEngine.projectDirectoryFile(projectKey),
                    picoConfiguration,
                    installedBuildCapabilities(cppExtensionInfo, picoExtensionInfo, pythonExtensionInfo, gitToolsExtensionInfo, gnuArmExtensionInfo),
                )
            }.getOrNull()
        } else null
        if (requirementReport?.errors?.isNotEmpty() == true) {
            val report = requirementReport.render()
            terminal = report
            buildOutput = report
            diagnosticsOutput = report
            return
        }
        val preflightNotes = requirementReport?.takeIf { it.requirements.isNotEmpty() }?.render()
        diagnosticsOutput = preflightNotes.orEmpty()
        val picoPlatform = if (picoProject) picoBoard(projectSources).platform.uppercase() else ""
        val prompt = projectName.ifBlank { "workspace" }
        val initialTerminalOutput = when {
            picoProject -> "$prompt $ pico build\nBuilding $picoPlatform firmware…"
            pythonProject -> "$prompt $ python -m py_compile $pythonEntry\nChecking syntax…"
            gnuLanguageProject == GnuProjectLanguage.Fortran -> "$prompt $ gfortran … -o program\nBuilding Fortran project…"
            gnuLanguageProject == GnuProjectLanguage.Cobol -> "$prompt $ cobc -x -free … -o program\nBuilding COBOL project…"
            else -> "$prompt $ clang++ *.cpp -o program\nBuilding…"
        }
        val managedProgramStarted = AtomicBoolean(false)
        val hostManagedSessionId = if (!picoProject) {
            terminalController.beginManagedCommand(terminal, "$initialTerminalOutput\n")
        } else null
        managedTerminalSessionId = hostManagedSessionId
        building = true
        if (hostManagedSessionId == null) terminal = initialTerminalOutput
        buildOutput = when {
            picoProject -> "Building $picoPlatform firmware…"
            pythonProject -> "Checking and running $pythonEntry…"
            gnuLanguageProject != null -> "Building ${gnuLanguageProject.displayName} project…"
            else -> "Building project…"
        }
        Thread {
            val result = runCatching {
                if (picoProject) {
                    val configuration = picoConfiguration ?: error("Missing Pico project configuration")
                    val fullRuntime = picoRuntimeDelivery.runtimeOrNull()
                        ?: error("Full Pico CMake runtime disappeared after installation")
                    val projectDirectory = gitEngine.projectDirectoryFile(projectKey)
                    // Parse every CMake project here instead of relying on the
                    // requirement scanner's broad remote-dependency hint. The
                    // resolver is intentionally a no-op when no FetchContent Git
                    // declarations exist, while a false-negative would let CMake
                    // clone into build/_deps and invoke an unavailable external
                    // git-submodule helper.
                    val dependencies = cmakeDependencyResolver.resolve(
                        projectDirectory,
                        allowDownload = configuration.networkPolicy == PicoDependencyNetworkPolicy.Online,
                    ) { progress ->
                        postToMainThread {
                            if (building) {
                                terminal = "${promptedCommand("pico dependencies")}\n$progress"
                                buildOutput = progress
                            }
                        }
                    }
                    val sdk = PicoSdkBundle(context.applicationContext, configuration.board).apply { install() }
                    val cmakeSdk = PicoCMakeSdkBundle(context.applicationContext).apply { install() }
                    val full = fullRuntime.build(
                        picoRuntimeDelivery.buildRequest(
                            projectDirectory,
                            configuration,
                            cmakeSdk.sdkRoot,
                            sdk.cmakeCompilerHeadersDirectory,
                            sdk.runtimeDirectory,
                            dependencies.sourceDirectories,
                            parallelJobs = appearance.buildJobs,
                            thermalAware = appearance.thermalAware,
                            debugBuild = debug,
                        ),
                    ) { rawProgress ->
                        val progress = visiblePicoBuildProgress(rawProgress) ?: return@build
                        postToMainThread {
                            if (building) {
                                terminal = "${promptedCommand("pico build")}\n$progress"
                                buildOutput = progress
                            }
                        }
                    }
                    BuildResult(
                        full.succeeded,
                        (dependencies.messages + full.output).joinToString("\n"),
                    )
                } else if (gnuLanguageProject != null) {
                    val projectDirectory = projectKey.takeIf { it.startsWith("project:") }
                        ?.let(gitEngine::projectDirectoryFile)
                        ?: error("Open a project before compiling ${gnuLanguageProject.displayName}")
                    gitEngine.saveProject(projectKey, projectSources, folders)
                    gnuLanguagesCompiler.compileAndRun(
                        projectDirectory,
                        gnuLanguageProject,
                        debug = debug,
                        onProgramStarted = {
                            postToMainThread {
                                terminalBeforeEditing = null
                                terminalVisible = true
                                consoleActive = true
                                managedProgramStarted.set(true)
                                hostManagedSessionId?.let {
                                    terminalController.appendManagedOutput(it, "$prompt $ ./program\n")
                                } ?: run { terminal = "$prompt $ ./program\n" }
                            }
                        },
                        onProgramOutput = { chunk ->
                            postToMainThread {
                                hostManagedSessionId?.let { terminalController.appendManagedOutput(it, chunk) }
                                    ?: run { terminal += chunk }
                            }
                        },
                    )
                } else if (pythonProject) {
                    val projectDirectory = projectKey.takeIf { it.startsWith("project:") }
                        ?.let(gitEngine::projectDirectoryFile)
                    if (projectDirectory != null) {
                        gitEngine.saveProject(projectKey, projectSources, folders)
                    }
                    engine.runPython(
                        projectSources,
                        pythonEntry ?: error("Missing Python entry file"),
                        projectDirectory = projectDirectory,
                        debug = debug,
                        onProgramStarted = {
                            postToMainThread {
                                terminalBeforeEditing = null
                                terminalVisible = true
                                consoleActive = true
                                managedProgramStarted.set(true)
                                val runCommand = if (debug) {
                                    "$prompt $ python -m pdb $pythonEntry\n"
                                } else {
                                    "$prompt $ python $pythonEntry\n"
                                }
                                hostManagedSessionId?.let {
                                    terminalController.appendManagedOutput(it, runCommand)
                                } ?: run { terminal = runCommand }
                            }
                        },
                        onProgramOutput = { chunk ->
                            postToMainThread {
                                hostManagedSessionId?.let { terminalController.appendManagedOutput(it, chunk) }
                                    ?: run { terminal += chunk }
                            }
                        },
                    )
                } else {
                    engine.compileAndRun(
                        projectSources,
                        debug = debug,
                        onProgramStarted = {
                            postToMainThread {
                                terminalBeforeEditing = null
                                terminalVisible = true
                                consoleActive = true
                                managedProgramStarted.set(true)
                                hostManagedSessionId?.let {
                                    terminalController.appendManagedOutput(it, "$prompt $ ./program\n")
                                } ?: run { terminal = "$prompt $ ./program\n" }
                            }
                        },
                        onProgramOutput = { chunk ->
                            postToMainThread {
                                hostManagedSessionId?.let { terminalController.appendManagedOutput(it, chunk) }
                                    ?: run { terminal += chunk }
                            }
                        },
                    )
                }
            }.getOrElse { error -> BuildResult(false, "Build failed: ${error.message ?: error.javaClass.simpleName}") }
            val advisedOutput = BuildFailureAdvisor.appendAdvice(result.output)
            val visibleOutput = listOfNotNull(preflightNotes, advisedOutput).joinToString("\n\n")
            postToMainThread {
                consoleActive = false
                if (hostManagedSessionId != null) {
                    val finalTerminalOutput = when {
                        result.wasCancelledByUser() || result.succeeded -> null
                        managedProgramStarted.get() -> result.output.lineSequence()
                            .map(String::trim)
                            .lastOrNull { it.startsWith("Process exited with") }
                        pythonProject || gnuLanguageProject != null -> visibleOutput.trimEnd()
                        else -> terminalBuildSummary(false, result, visibleOutput, prompt)
                    }
                    terminalController.finishManagedCommand(hostManagedSessionId, finalTerminalOutput)
                    if (managedTerminalSessionId == hostManagedSessionId) managedTerminalSessionId = null
                } else {
                    terminal = terminalBuildSummary(picoProject, result, visibleOutput, prompt)
                }
                buildOutput = visibleOutput
                if (!result.succeeded && !result.wasCancelledByUser()) diagnosticsOutput = visibleOutput
                building = false
                if (projectKey.startsWith("project:")) projectTree = gitEngine.projectTree(projectKey)
            }
        }.start()
    }

    fun startDebugging() {
        if (building || cleaningPicoBuild || debugController.active) return
        val webProject = !isPicoSdkProject(projectSources) &&
            ("package.json" in projectSources || projectSources.keys.any(::isWebIntelligenceFile))
        if (webProject) {
            if (webExtensionInfo == null) {
                terminal = "Install the Web Development extension before debugging this project"
                openWebExtensionDetails()
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            val projectDirectory = gitEngine.projectDirectoryFile(projectKey)
            val packageJson = projectSources["package.json"].orEmpty()
            val browserProject = "package.json" in projectSources && (
                "vite" in packageJson || "react" in packageJson ||
                    projectSources.keys.any { it.endsWith(".tsx", true) || it.endsWith(".jsx", true) }
                )
            val entryName = listOf("server.js", "server.mjs", "index.js", "index.mjs")
                .firstOrNull { it in projectSources }
                ?: activeFileName.takeIf {
                    it in projectSources && listOf(".js", ".mjs", ".cjs").any { suffix ->
                        it.endsWith(suffix, ignoreCase = true)
                    }
                }
            if (entryName == null && !browserProject) {
                terminal = "V8 debugging needs a Node.js entry file or a browser project with package.json."
                terminalVisible = true
                return
            }
            val runtime = File(context.filesDir, "web-runtime")
            val adapter = listOf(
                File(runtime, "lib/js-debug/src/dapDebugServer.js"),
                File(runtime, "lib/node_modules/@vscode/js-debug/src/dapDebugServer.js"),
                File(runtime, "lib/node_modules/vscode-js-debug/src/dapDebugServer.js"),
            ).firstOrNull(File::isFile)
            if (adapter == null) {
                terminal = "Install or update Web Development to add the V8 Debug Adapter"
                openWebExtensionDetails()
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            val debugWebServerSessionId = if (browserProject) {
                terminalController.webServerSessionId()?.let { existing ->
                    focusRunningWebServer(
                        existing,
                        "A web server is already running. Stop it before starting browser debugging.",
                    )
                    return
                }
                cleanStaleWebServers()
                if (terminalController.activeCommandRunning()) {
                    terminal = appendWorkspaceOutput(
                        terminal,
                        "\nCurrent terminal is busy. Select an idle terminal before starting browser debugging.\n",
                    )
                    terminalController.syncActive(terminal)
                    return
                }
                terminalController.reserveActiveWebServer(terminal) ?: run {
                    terminal = appendWorkspaceOutput(terminal, "\nCould not reserve this terminal for browser debugging.\n")
                    terminalController.syncActive(terminal)
                    return
                }
            } else null
            focusManager.clearFocus(force = true)
            terminalBeforeEditing = null
            terminalVisible = true
            destination = ActivityDestination.Run
            sidePanelVisible = true
            building = true
            if (debugWebServerSessionId != null) {
                terminalController.replaceSessionTranscript(
                    debugWebServerSessionId,
                    "Preparing browser debug session…",
                    commandRunning = false,
                )
            } else {
                terminal = "Preparing V8 debug session…"
            }
            Thread({
                runCatching { gitEngine.saveProject(projectKey, projectSources, folders) }
                val preparedWebProject = if (browserProject) {
                    runCatching { webProjectRuntime.prepare(projectKey, projectDirectory) }.getOrElse { error ->
                        postToMainThread {
                            building = false
                            requireNotNull(debugWebServerSessionId).let(terminalController::releaseWebServer)
                            terminalController.replaceSessionTranscript(
                                requireNotNull(debugWebServerSessionId),
                                "Could not prepare Web project: ${error.message ?: error.javaClass.simpleName}",
                                commandRunning = false,
                            )
                        }
                        return@Thread
                    }
                } else null
                if (preparedWebProject != null) {
                    val commandDispatched = AtomicBoolean(false)
                    val commandDispatchFinished = CountDownLatch(1)
                    postToMainThread {
                        commandDispatched.set(
                            requireNotNull(debugWebServerSessionId).let(terminalController::engine)
                                ?.sendCommand(preparedWebProject.startCommand()) == true,
                        )
                        if (commandDispatched.get()) {
                            terminalController.replaceSessionTranscript(
                                requireNotNull(debugWebServerSessionId),
                                "Starting web server for browser debugging…\n",
                                commandRunning = true,
                            )
                        } else {
                            requireNotNull(debugWebServerSessionId).let(terminalController::releaseWebServer)
                        }
                        commandDispatchFinished.countDown()
                    }
                    commandDispatchFinished.await(2, TimeUnit.SECONDS)
                    val ready = commandDispatched.get() &&
                        webProjectRuntime.waitUntilReady("http://127.0.0.1:3000")
                    if (ready) Thread.sleep(WEB_SERVER_START_STABILITY_MILLIS)
                    if (!ready || !terminalController.isRunningWebServer(requireNotNull(debugWebServerSessionId))) {
                        postToMainThread {
                            building = false
                            terminalController.appendSessionTranscript(
                                requireNotNull(debugWebServerSessionId),
                                "\nCould not start the web server at http://127.0.0.1:3000\n",
                            )
                        }
                        return@Thread
                    }
                    postToMainThread { openWebPreview() }
                }
                val environment = mapOf(
                    "FOLDCODE_WEB_ROOT" to runtime.absolutePath,
                    "NODE_PATH" to listOf(
                        File(preparedWebProject?.directory ?: projectDirectory, "node_modules").absolutePath,
                        File(runtime, "lib/node_modules").absolutePath,
                    ).joinToString(":"),
                    "LD_LIBRARY_PATH" to listOf(
                        File(runtime, "lib").absolutePath,
                        context.applicationInfo.nativeLibraryDir,
                    ).joinToString(":"),
                    "HOME" to projectDirectory.absolutePath,
                    "TMPDIR" to context.cacheDir.absolutePath,
                )
                val launch = debugController.start(
                    if (browserProject) DebugLaunchTarget.WebBrowser(
                        project = projectDirectory,
                        displayName = projectName,
                        url = "http://127.0.0.1:3000",
                        nodeExecutable = File(context.applicationInfo.nativeLibraryDir, "foldnode.so"),
                        adapterScript = adapter,
                        environment = environment,
                    ) else DebugLaunchTarget.WebNode(
                        project = projectDirectory,
                        displayName = projectName,
                        entryFile = File(projectDirectory, requireNotNull(entryName)),
                        nodeExecutable = File(context.applicationInfo.nativeLibraryDir, "foldnode.so"),
                        adapterScript = adapter,
                        environment = environment,
                    ),
                    DebugBreakpointStore.all(),
                )
                postToMainThread {
                    building = false
                    val launchMessage = launch.fold(
                        onSuccess = { "V8 debug session started\n" },
                        onFailure = { "Could not start V8 debugging: ${it.message ?: it.javaClass.simpleName}" },
                    )
                    if (debugWebServerSessionId != null) {
                        terminalController.appendSessionTranscript(debugWebServerSessionId, "\n$launchMessage")
                    } else {
                        terminal = launchMessage
                    }
                }
            }, "FoldCode-V8-launch").start()
            return
        }
        val hostCppProject = !isPicoSdkProject(projectSources) &&
            !isRustCargoProject(projectSources) &&
            projectSources.keys.any {
                it.endsWith(".c", true) || it.endsWith(".cpp", true) ||
                    it.endsWith(".cc", true) || it.endsWith(".cxx", true)
            }
        if (!hostCppProject) {
            // Python keeps its interactive pdb flow while Rust/Pico acquire
            // their extension-specific debug targets.
            runCurrentProject(debug = true)
            return
        }
        if (cppExtensionInfo == null) {
            terminal = "Install the C/C++ extension before debugging this project"
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            return
        }
        focusManager.clearFocus(force = true)
        terminalBeforeEditing = null
        terminalVisible = true
        destination = ActivityDestination.Run
        sidePanelVisible = true
        building = true
        terminal = "${promptedCommand("clang/clang++ sources -O0 -g")}\nPreparing LLDB debug executable…"
        buildOutput = terminal
        Thread({
            val result = engine.compileAndRun(
                projectSources,
                debug = true,
                runAfterBuild = false,
            )
            if (!result.succeeded) {
                postToMainThread {
                    building = false
                    terminal = result.output
                    buildOutput = result.output
                    diagnosticsOutput = result.output
                }
                return@Thread
            }
            val launch = debugController.start(
                DebugLaunchTarget.AndroidHost(
                    project = engine.debugWorkspace,
                    displayName = projectName,
                    executable = engine.debugExecutable,
                ),
                DebugBreakpointStore.all(),
            )
            postToMainThread {
                building = false
                buildOutput = result.output
                terminal = launch.fold(
                    onSuccess = { "LLDB debug session started\n" },
                    onFailure = { "Could not start debugging: ${it.message ?: it.javaClass.simpleName}" },
                )
            }
        }, "FoldCode-LLDB-launch").start()
    }

    fun stopCompile() {
        if (!building) return
        val managedSession = managedTerminalSessionId
        if (managedSession != null) {
            terminalController.interruptManagedCommand(managedSession)
            consoleActive = false
        } else {
            terminal = "Stopping build…"
            buildOutput = "Stopping build…"
        }
        picoRuntimeDelivery.runtimeOrNull()?.cancel()
        rustPicoCompiler.cancel()
        gnuLanguagesCompiler.cancel()
        engine.cancel()
    }

    fun stopCurrentOperation() {
        if (building) {
            stopCompile()
            return
        }
        if (terminalController.interruptWebServer(terminal)) {
            terminalCommandRunning = terminalController.activeCommandRunning()
            consoleActive = false
            return
        }
        if (terminalCommandRunning && terminalController.interruptActive(terminal)) {
            terminalCommandRunning = false
            consoleActive = false
            return
        }
    }

    fun runPicotool(command: String) {
        if (picotoolBusy) return
        if (picoExtensionInfo == null || !picotoolRunner.isAvailable()) {
            terminal = "${promptedCommand("picotool $command")}\nInstall the Raspberry Pi Pico extension to use picotool\n"
            return
        }
        picotoolBusy = true
        terminalCommandRunning = true
        terminal = "${promptedCommand("picotool $command")}\n"
        val workingDirectory = projectKey.takeIf(String::isNotBlank)?.let(gitEngine::projectDirectoryFile)
        picotoolRunner.runAsync(
            command,
            workingDirectory,
            status = { terminal += "$it\n" },
        ) { result ->
            terminal += result.fold(
                onSuccess = {
                    buildString {
                        appendLine(it.output)
                        if (it.exitCode != 0) append("Exit code: ${it.exitCode}")
                    }.trimEnd() + "\n"
                },
                onFailure = { "Failed: ${it.message ?: it.javaClass.simpleName}\n" },
            )
            picotoolBusy = false
            terminalCommandRunning = false
        }
    }

    fun sendConsoleInput(value: String) {
        val command = value.trim()
        val activeTerminal = terminalController.activeEngine()
        val shellCommandMode = !consoleActive && debugSnapshot.status == DebugSessionStatus.Idle
        val packageWebProject = "package.json" in projectSources
        val existingWebServer = terminalController.webServerSessionId()
        if (shellCommandMode && packageWebProject && isWebServerCommand(command) && existingWebServer != null) {
            focusRunningWebServer(
                existingWebServer,
                "This project already has a running web server. Stop it before starting another.",
            )
            return
        }
        if (shellCommandMode && packageWebProject && isNpmProjectMutation(command) && existingWebServer != null) {
            terminal = appendWorkspaceOutput(
                terminal,
                "\nStop the running web server before changing this project's npm dependencies.\n",
            )
            terminalController.syncActive(terminal)
            return
        }
        val manualWebServerSession = if (
            shellCommandMode && packageWebProject && isWebServerCommand(command)
        ) {
            cleanStaleWebServers()
            if (terminalController.activeCommandRunning()) {
                terminal = appendWorkspaceOutput(terminal, "\nCurrent terminal is busy. Select an idle terminal first.\n")
                terminalController.syncActive(terminal)
                return
            }
            terminalController.reserveActiveWebServer(terminal) ?: run {
                terminal = appendWorkspaceOutput(terminal, "\nCould not reserve this terminal for the web server.\n")
                terminalController.syncActive(terminal)
                return
            }
        } else null
        if (debugSnapshot.status == DebugSessionStatus.Running && debugController.sendInput(value)) {
            terminal += "$value\n"
            terminalController.syncActive(terminal)
        } else if (debugSnapshot.status == DebugSessionStatus.Paused) {
            terminal += "\nProgram is paused. Continue debugging before sending input.\n"
            terminalController.syncActive(terminal)
        } else if (!consoleActive && command == "clear") {
            terminal = ""
            terminalController.syncActive("")
        } else if (!consoleActive && (command == "help" || command == "help -l" || command == "help --list")) {
            terminal = buildString {
                if (terminal.isNotBlank()) append(terminal.trimEnd()).append('\n')
                append(terminalController.activePrompt).append(" $ ").append(value).append('\n')
                append(activeTerminal?.helpText(command != "help") ?: "No terminal session").append('\n')
            }
            terminalController.syncActive(terminal)
        } else if (
            consoleActive &&
            (managedTerminalSessionId == null || managedTerminalSessionId == terminalController.selectedId) &&
            (gnuLanguagesCompiler.sendInput(value) || rustPicoCompiler.sendInput(value) || engine.sendInput(value))
        ) {
            managedTerminalSessionId?.let { terminalController.appendManagedOutput(it, "$value\n") }
                ?: run {
                    terminal += "$value\n"
                    terminalController.syncActive(terminal)
                }
        } else if (terminalCommandRunning && activeTerminal?.sendInput(value) == true) {
            terminal += "$value\n"
            terminalController.syncActive(terminal)
        } else if (!consoleActive && value.trim().let { it == "picotool" || it.startsWith("picotool ") }) {
            runPicotool(value.trim().removePrefix("picotool").trim())
        } else if (!consoleActive && activeTerminal?.sendCommand(value) == true) {
            terminalCommandRunning = true
            terminal = buildString {
                if (terminal.isNotBlank()) append(terminal.trimEnd()).append('\n')
                append(terminalController.activePrompt).append(" $ ").append(value).append('\n')
            }
            terminalController.syncActive(terminal, commandRunning = true)
        } else {
            manualWebServerSession?.let(terminalController::releaseWebServer)
            consoleActive = false
            terminal += "\nTerminal process is unavailable\n"
        }
    }

    fun configurePicoProject(configuration: PicoProjectConfiguration) {
        val rustProject = isPicoRustProject(projectSources)
        if (!isPicoSdkProject(projectSources) && !rustProject) return
        if (PICO_PROJECT_MARKER in files) {
            // FoldCode-created projects carry portable metadata by design.
            val marker = picoConfigurationMarker(configuration, runtime = if (rustProject) "rust" else null)
            files = files + (PICO_PROJECT_MARKER to ProjectFile(PICO_PROJECT_MARKER, marker))
            picoConfigurationStore.remove(projectKey)
        } else {
            // A PC-created project stays byte-for-byte compatible with its source
            // repository; only the app remembers the selected local build preset.
            picoConfigurationStore.save(projectKey, configuration)
        }
        picoConfigurationRevision++
        terminal = buildString {
            append("Configured ${configuration.board.displayName} · ")
            if (rustProject) {
                append("Cargo · ${configuration.buildType.displayName}")
                append("\nCargo will compile this project in ${configuration.buildType.displayName.lowercase()} mode.")
            } else {
                append("Full CMake")
                configuration.target?.let { append(" · $it") }
                append("\nFull CMake runtime will be used to compile this project.")
            }
        }
    }

    fun runPicoUsb() {
        val sdkProject = isPicoSdkProject(projectSources)
        val rustProject = isPicoRustProject(projectSources)
        if (building || cleaningPicoBuild || (!sdkProject && !rustProject)) return
        if (picoExtensionInfo == null) {
            terminal = "Install the Raspberry Pi Pico extension before flashing this project"
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            drawerOpen = true
            return
        }
        building = true
        if (rustProject) rustIntelligence.reset()
        terminal = "USB flash 1/3 · checking for an up-to-date UF2…"
        Thread {
            val projectDirectory = gitEngine.projectDirectoryFile(projectKey)
            val configuration = currentPicoConfiguration()
            if (sdkProject && picoExtensionInfo?.components?.none { it.id == configuration.board.bundleId && it.installed } != false) {
                postToMainThread {
                    building = false
                    terminal = "Install the ${configuration.board.displayName} component before building or flashing this project"
                    openPicoExtensionDetails()
                    destination = ActivityDestination.Extensions
                }
                return@Thread
            }
            val result = runCatching {
                val existingUf2 = findCurrentPicoUf2(projectDirectory, configuration.target)
                if (existingUf2 != null) {
                    return@runCatching PicoBuildResult(
                        succeeded = true,
                        output = "Using up-to-date UF2: ${existingUf2.absolutePath}",
                        uf2File = existingUf2,
                    )
                }
                if (rustProject) {
                    require(rustExtensionInfo != null) { "Install the Rust extension before building this project" }
                    rustPicoCompiler.build(
                        projectDirectory,
                        configuration.board,
                        debug = configuration.buildType == PicoBuildType.Debug,
                    ) { progress ->
                        postToMainThread {
                            if (building) {
                                terminal = "USB flash 1/3 · compiling Rust firmware…\n$progress"
                                buildOutput = progress
                            }
                        }
                    }
                } else {
                    val runtime = picoRuntimeDelivery.runtimeOrNull()
                        ?: error("The native Full Pico CMake runtime is unavailable")
                    val boardSdk = PicoSdkBundle(context.applicationContext, configuration.board).apply { install() }
                    val cmakeSdk = PicoCMakeSdkBundle(context.applicationContext).apply { install() }
                    val full = runtime.build(
                        picoRuntimeDelivery.buildRequest(
                            projectDirectory,
                            configuration,
                            cmakeSdk.sdkRoot,
                            boardSdk.cmakeCompilerHeadersDirectory,
                            boardSdk.runtimeDirectory,
                            parallelJobs = appearance.buildJobs,
                            thermalAware = appearance.thermalAware,
                        ),
                    ) { rawProgress ->
                        val progress = visiblePicoBuildProgress(rawProgress) ?: return@build
                        postToMainThread {
                            if (building) {
                                terminal = "USB flash 1/3 · compiling firmware…\n$progress"
                                buildOutput = progress
                            }
                        }
                    }
                    PicoBuildResult(
                        succeeded = full.succeeded,
                        output = full.output,
                        uf2File = findCurrentPicoUf2(projectDirectory, configuration.target)
                            ?: full.artifacts.asSequence().map(::File)
                                .filter { it.extension.equals("uf2", ignoreCase = true) }
                                .filter { configuration.target.isNullOrBlank() || it.nameWithoutExtension == configuration.target }
                                .maxByOrNull(File::lastModified),
                    )
                }
            }.getOrElse { error ->
                PicoBuildResult(false, "Build failed: ${error.message ?: error.javaClass.simpleName}")
            }
            postToMainThread {
                if (rustProject) registerGeneratedCargoLock(projectDirectory)
                buildOutput = result.output
                if (!result.succeeded || result.uf2File == null) {
                    building = false
                    terminal = result.output
                } else {
                    terminal = "${result.output.lineSequence().lastOrNull().orEmpty()}\nUSB flash 2/3 · searching for a Pico in BOOTSEL mode…"
                    picoUsbFlasher.flash(
                        uf2 = result.uf2File,
                        status = { terminal = "USB flash 3/3 · $it" },
                        finished = { flashResult ->
                            building = false
                            terminal = flashResult.fold(
                                onSuccess = {
                                    "USB flash complete · 100%\n${result.output.lineSequence().lastOrNull().orEmpty()}\nThe Pico accepted ${result.uf2File.name} and is rebooting."
                                },
                                onFailure = {
                                    "${result.output.lineSequence().lastOrNull().orEmpty()}\nUSB flash failed: ${it.message}"
                                },
                            )
                        },
                    )
                }
            }
        }.start()
    }

    fun configurePicoCMake() {
        if (building || cleaningPicoBuild || !projectKey.startsWith("project:")) return
        if (isPicoRustProject(projectSources)) {
            if (rustExtensionInfo == null) {
                terminal = "Install the Rust extension before configuring Cargo"
                destination = ActivityDestination.Extensions
                sidePanelVisible = true
                return
            }
            rustIntelligence.reset()
            building = true
            terminal = "${promptedCommand("cargo metadata --format-version=1 --no-deps")}\nConfiguring Cargo…"
            buildOutput = "Configuring Rust project…"
            val configuringProjectKey = projectKey
            val configuration = currentPicoConfiguration()
            Thread {
                val projectDirectory = gitEngine.projectDirectoryFile(configuringProjectKey)
                val result = runCatching {
                    gitEngine.saveProject(
                        configuringProjectKey,
                        rustSourcesForSave(projectDirectory),
                        folders,
                    )
                    rustPicoCompiler.configure(
                        projectDirectory,
                        configuration.board,
                    ) { progress ->
                        postToMainThread {
                            if (building && projectKey == configuringProjectKey) {
                                terminal = "${promptedCommand("cargo metadata --format-version=1 --no-deps")}\n$progress"
                                buildOutput = progress
                            }
                        }
                    }
                }.getOrElse { error ->
                    PicoBuildResult(false, "Cargo configuration failed: ${error.message ?: error.javaClass.simpleName}")
                }
                postToMainThread {
                    if (projectKey == configuringProjectKey) {
                        registerGeneratedCargoLock(projectDirectory)
                        terminal = result.output
                        buildOutput = result.output
                        if (!result.succeeded) diagnosticsOutput = result.output
                    }
                    building = false
                }
            }.start()
            return
        }
        if (!isPicoSdkProject(projectSources)) return
        if (picoExtensionInfo == null) {
            terminal = "Install the Raspberry Pi Pico extension before configuring CMake"
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            return
        }
        val configuration = currentPicoConfiguration()
        if (picoExtensionInfo?.components?.none { it.id == configuration.board.bundleId && it.installed } != false) {
            terminal = "Install the ${configuration.board.displayName} component before configuring CMake"
            openPicoExtensionDetails()
            destination = ActivityDestination.Extensions
            return
        }
        val runtime = picoRuntimeDelivery.runtimeOrNull()
        if (runtime == null) {
            terminal = "The native Full Pico CMake runtime is unavailable"
            destination = ActivityDestination.Extensions
            return
        }
        building = true
        terminal = "${promptedCommand("cmake -S . -B build -G Ninja")}\nConfiguring CMake…"
        buildOutput = "Configuring Full Pico CMake project…"
        val configuringProjectKey = projectKey
        Thread {
            val result = runCatching {
                val projectDirectory = gitEngine.projectDirectoryFile(configuringProjectKey)
                val requirements = PicoProjectRequirementScanner.scan(
                    projectDirectory,
                    configuration,
                    installedBuildCapabilities(cppExtensionInfo, picoExtensionInfo, pythonExtensionInfo, gitToolsExtensionInfo, gnuArmExtensionInfo),
                )
                if (requirements.errors.isNotEmpty()) {
                    return@runCatching PicoFullBuildResult(false, requirements.render())
                }
                val dependencies = cmakeDependencyResolver.resolve(
                    projectDirectory,
                    allowDownload = configuration.networkPolicy == PicoDependencyNetworkPolicy.Online,
                ) { progress ->
                    postToMainThread {
                        if (building && projectKey == configuringProjectKey) {
                            terminal = "${promptedCommand("cmake dependencies")}\n$progress"
                            buildOutput = progress
                        }
                    }
                }
                val sdk = PicoSdkBundle(context.applicationContext, configuration.board).apply { install() }
                val cmakeSdk = PicoCMakeSdkBundle(context.applicationContext).apply { install() }
                val configured = runtime.build(
                    picoRuntimeDelivery.buildRequest(
                        projectDirectory,
                        configuration,
                        cmakeSdk.sdkRoot,
                        sdk.cmakeCompilerHeadersDirectory,
                        sdk.runtimeDirectory,
                        dependencies.sourceDirectories,
                        configureOnly = true,
                        parallelJobs = appearance.buildJobs,
                        thermalAware = appearance.thermalAware,
                    ),
                ) { rawProgress ->
                    val progress = visiblePicoBuildProgress(rawProgress) ?: return@build
                    postToMainThread {
                        if (building && projectKey == configuringProjectKey) {
                            terminal = "${promptedCommand("cmake -S . -B build -G Ninja")}\n$progress"
                            buildOutput = progress
                        }
                    }
                }
                configured.copy(output = (dependencies.messages + configured.output).joinToString("\n"))
            }.getOrElse { error ->
                PicoFullBuildResult(false, "CMake configuration failed: ${error.message ?: error.javaClass.simpleName}")
            }
            val refreshedTree = runCatching { gitEngine.projectTree(configuringProjectKey) }.getOrDefault(emptyList())
            val refreshedTargets = runCatching { gitEngine.cmakeExecutableTargets(configuringProjectKey) }.getOrDefault(emptyList())
            postToMainThread {
                if (projectKey == configuringProjectKey) {
                    projectTree = refreshedTree
                    picoCMakeTargets = refreshedTargets
                    buildOutput = result.output
                    terminal = if (result.succeeded) "CMake configuration succeeded" else result.output
                    if (!result.succeeded) diagnosticsOutput = result.output
                }
                building = false
            }
        }.start()
    }

    fun cleanPicoBuild() {
        if (building || cleaningPicoBuild || !projectKey.startsWith("project:")) return
        if (isPicoRustProject(projectSources)) {
            cleaningPicoBuild = true
            terminal = "Cleaning Cargo build outputs…"
            buildOutput = terminal
            val cleaningProjectKey = projectKey
            Thread {
                val result = runCatching {
                    rustPicoCompiler.clean(gitEngine.projectDirectoryFile(cleaningProjectKey))
                }.getOrElse { error ->
                    PicoBuildResult(false, "Cargo clean failed: ${error.message ?: error.javaClass.simpleName}")
                }
                val refreshedTree = runCatching { gitEngine.projectTree(cleaningProjectKey) }.getOrDefault(emptyList())
                postToMainThread {
                    cleaningPicoBuild = false
                    if (projectKey == cleaningProjectKey) {
                        projectTree = refreshedTree
                        terminal = result.output
                        buildOutput = result.output
                        if (!result.succeeded) diagnosticsOutput = result.output
                    }
                }
            }.start()
            return
        }
        if (!isPicoSdkProject(projectSources)) return
        val runtime = picoRuntimeDelivery.runtimeOrNull()
        if (picoExtensionInfo == null || runtime == null) {
            terminal = "Install the Raspberry Pi Pico extension before cleaning CMake"
            destination = ActivityDestination.Extensions
            sidePanelVisible = true
            return
        }
        val configuration = currentPicoConfiguration()
        if (picoExtensionInfo?.components?.none { it.id == configuration.board.bundleId && it.installed } != false) {
            terminal = "Install the ${configuration.board.displayName} component before cleaning CMake"
            openPicoExtensionDetails()
            destination = ActivityDestination.Extensions
            return
        }
        cleaningPicoBuild = true
        terminal = "Cleaning and reconfiguring CMake…"
        buildOutput = "Running CMake clean, then regenerating the project configuration…"
        val cleaningProjectKey = projectKey
        val directory = gitEngine.projectDirectoryFile(projectKey)
        Thread {
            val result = runCatching {
                // Clean, Configure and Compile share the same compatibility and
                // dependency preparation. Otherwise a FetchContent project can
                // configure in one action and fail in another.
                val requirements = PicoProjectRequirementScanner.scan(
                    directory,
                    configuration,
                    installedBuildCapabilities(cppExtensionInfo, picoExtensionInfo, pythonExtensionInfo, gitToolsExtensionInfo, gnuArmExtensionInfo),
                )
                if (requirements.errors.isNotEmpty()) {
                    return@runCatching PicoFullBuildResult(false, requirements.render())
                }
                val dependencies = cmakeDependencyResolver.resolve(
                    directory,
                    allowDownload = configuration.networkPolicy == PicoDependencyNetworkPolicy.Online,
                ) { progress ->
                    postToMainThread {
                        if (cleaningPicoBuild && projectKey == cleaningProjectKey) {
                            terminal = "${promptedCommand("cmake dependencies")}\n$progress"
                            buildOutput = progress
                        }
                    }
                }
                val sdk = PicoSdkBundle(context.applicationContext, configuration.board).apply { install() }
                val cmakeSdk = PicoCMakeSdkBundle(context.applicationContext).apply { install() }
                val cleaned = runtime.build(
                    picoRuntimeDelivery.buildRequest(
                        directory,
                        configuration,
                        cmakeSdk.sdkRoot,
                        sdk.cmakeCompilerHeadersDirectory,
                        sdk.runtimeDirectory,
                        dependencies.sourceDirectories,
                        cleanOnly = true,
                        parallelJobs = appearance.buildJobs,
                        thermalAware = appearance.thermalAware,
                    ),
                ) { rawProgress ->
                    val progress = visiblePicoBuildProgress(rawProgress) ?: return@build
                    postToMainThread {
                        if (cleaningPicoBuild && projectKey == cleaningProjectKey) {
                            terminal = "${promptedCommand("ninja -C build -t clean")}\n$progress"
                            buildOutput = progress
                        }
                    }
                }
                cleaned.copy(output = (dependencies.messages + cleaned.output).joinToString("\n"))
            }.getOrElse { error ->
                PicoFullBuildResult(false, "CMake clean failed: ${error.message ?: error.javaClass.simpleName}")
            }
            val refreshedTree = runCatching { gitEngine.projectTree(cleaningProjectKey) }.getOrDefault(emptyList())
            postToMainThread {
                cleaningPicoBuild = false
                if (projectKey == cleaningProjectKey) {
                    projectTree = refreshedTree
                    buildOutput = result.output
                    terminal = if (result.succeeded) "CMake has been cleaned and reconfigured." else result.output
                    if (!result.succeeded) diagnosticsOutput = result.output
                }
            }
        }.start()
    }

}

private const val WEB_SERVER_START_STABILITY_MILLIS = 500L

internal fun microPythonFirmwareReady(
    picoExtensionInfo: FoldCodeExtensionInfo,
    firmwareAvailable: Boolean,
): Boolean = firmwareAvailable && picoExtensionInfo.components.any {
    it.id == "micropython" && it.installed
}
