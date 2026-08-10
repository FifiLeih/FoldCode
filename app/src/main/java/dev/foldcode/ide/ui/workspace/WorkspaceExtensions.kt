package dev.foldcode.ide

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Owns extension lifecycle state and background installation operations.
 *
 * Keeping this feature outside the workspace composable prevents extension
 * delivery details from being coupled to editor, terminal, and layout state.
 */
internal class WorkspaceExtensionController(
    private val context: Context,
    private val extensionManager: FoldCodeExtensionManager,
    private val extensionMarketplace: FoldCodeExtensionMarketplace,
) {
    internal var cppExtensionInfo by mutableStateOf(extensionManager.cppInfo())
    internal var picoExtensionInfo by mutableStateOf(extensionManager.picoInfo())
    internal var pythonExtensionInfo by mutableStateOf(extensionManager.pythonInfo())
    internal var rustExtensionInfo by mutableStateOf(extensionManager.rustInfo())
    internal var gnuLanguagesExtensionInfo by mutableStateOf(extensionManager.gnuLanguagesInfo())
    internal var webExtensionInfo by mutableStateOf(extensionManager.webInfo())
    internal var gitToolsExtensionInfo by mutableStateOf(extensionManager.gitInfo())
    internal var gnuArmExtensionInfo by mutableStateOf(extensionManager.gnuArmInfo())
    internal var extensionBusy by mutableStateOf(false)
    internal var extensionNotice by mutableStateOf<String?>(null)

    private var installerLauncher: (() -> Unit)? = null

    internal fun bindInstallerLauncher(launcher: () -> Unit) {
        installerLauncher = launcher
    }

    internal fun openInstaller() {
        installerLauncher?.invoke()
    }

    internal fun installFromUri(uri: Uri?) {
        if (uri != null && !extensionBusy) {
            extensionBusy = true
            extensionNotice = "Validating extension package…"
            Thread {
                val installed = runCatching {
                    val info = context.contentResolver.openInputStream(uri)?.use(extensionManager::install)
                        ?: error("Could not read extension package")
                    when (info.id) {
                        "dev.foldcode.cpp" -> extensionManager.installCppRuntime()
                        "dev.foldcode.pico" -> extensionManager.preparePicoComponents(
                            bundledPicoComponentIds(info),
                        ) { name, completed, total ->
                            postToMainThread {
                                extensionNotice = "Installing Pico components $completed/$total · $name"
                            }
                        }
                        "dev.foldcode.python" -> extensionManager.installPythonRuntime()
                        "dev.foldcode.git" -> extensionManager.installGitRuntime()
                        "dev.foldcode.gnu-arm" -> extensionManager.installGnuArmRuntime()
                        "dev.foldcode.rust" -> extensionManager.installRustRuntime()
                        "dev.foldcode.gnu-languages" -> extensionManager.installGnuLanguagesRuntime()
                        "dev.foldcode.web" -> extensionManager.installWebRuntime()
                        else -> error("Unsupported extension ${info.id}")
                    }
                }
                postToMainThread {
                    extensionBusy = false
                    installed.onSuccess { info ->
                        when (info.id) {
                            "dev.foldcode.cpp" -> cppExtensionInfo = info
                            "dev.foldcode.python" -> pythonExtensionInfo = info
                            "dev.foldcode.git" -> gitToolsExtensionInfo = info
                            "dev.foldcode.gnu-arm" -> gnuArmExtensionInfo = info
                            "dev.foldcode.rust" -> rustExtensionInfo = info
                            "dev.foldcode.gnu-languages" -> gnuLanguagesExtensionInfo = info
                            "dev.foldcode.web" -> webExtensionInfo = info
                            else -> picoExtensionInfo = info
                        }
                        extensionNotice = "Installed ${info.name} ${info.version}"
                    }.onFailure { error ->
                        picoExtensionInfo = extensionManager.picoInfo()
                        extensionNotice = "Installation failed: ${error.message ?: "Invalid package"}"
                    }
                }
            }.start()
        }
    }

    fun installCppFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Downloading C/C++ compiler…"
        Thread {
            val installed = runCatching {
                val extension = extensionMarketplace.cppExtension()
                extensionMarketplace.downloadAndInstall(extension, extensionManager, context.cacheDir) { downloaded, total ->
                    postToMainThread {
                        extensionNotice = "Downloading C/C++ compiler… ${if (total > 0) downloaded * 100 / total else 0}%"
                    }
                }
                extensionManager.installCppRuntime()
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess { info ->
                    cppExtensionInfo = info
                    extensionNotice = "Installed ${info.name} ${info.version}"
                }.onFailure { extensionNotice = "Installation failed: ${it.message ?: "Server download failed"}" }
            }
        }.start()
    }

    fun installPicoFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Contacting FoldCode extension server…"
        Thread {
            var lastProgressUpdate = 0L
            val installed = runCatching {
                if (!extensionManager.isCppInstalled()) {
                    val dependency = extensionMarketplace.cppExtension()
                    extensionMarketplace.downloadAndInstall(dependency, extensionManager, context.cacheDir) { downloaded, total ->
                        postToMainThread {
                            extensionNotice = "Installing C/C++ dependency… ${if (total > 0) downloaded * 100 / total else 0}%"
                        }
                    }
                    extensionManager.installCppRuntime()
                }
                val extension = extensionMarketplace.picoExtension()
                extensionMarketplace.downloadAndInstall(
                    extension = extension,
                    manager = extensionManager,
                    cacheDirectory = context.cacheDir,
                ) { downloaded, total ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgressUpdate >= 250 || downloaded == total) {
                        lastProgressUpdate = now
                        postToMainThread {
                            val percent = if (total > 0) downloaded * 100 / total else 0
                            extensionNotice = "Downloading ${extension.name}… $percent%"
                        }
                    }
                }
                extensionManager.preparePicoComponents(setOf("core")) { name, completed, total ->
                    postToMainThread {
                        extensionNotice = "Installing core $completed/$total · $name"
                    }
                }
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess { info ->
                    cppExtensionInfo = extensionManager.cppInfo()
                    picoExtensionInfo = info
                    extensionNotice = "Installed ${info.name} ${info.version}"
                }.onFailure { error ->
                    extensionNotice = "Installation failed: ${error.message ?: "Server download failed"}"
                }
            }
        }.start()
    }

    fun installPythonFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Downloading Python runtime…"
        Thread {
            val installed = runCatching {
                val extension = extensionMarketplace.pythonExtension()
                extensionMarketplace.downloadAndInstall(extension, extensionManager, context.cacheDir) { downloaded, total ->
                    postToMainThread {
                        extensionNotice = "Downloading Python… ${if (total > 0) downloaded * 100 / total else 0}%"
                    }
                }
                extensionManager.installPythonRuntime()
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess {
                    pythonExtensionInfo = it
                    extensionNotice = "Installed ${it.name} ${it.version}"
                }.onFailure { extensionNotice = "Installation failed: ${it.message ?: "Server download failed"}" }
            }
        }.start()
    }

    fun uninstallPythonExtension() {
        if (extensionBusy) return
        extensionBusy = true
        Thread {
            val removed = extensionManager.uninstallPython()
            postToMainThread {
                extensionBusy = false
                if (removed) {
                    pythonExtensionInfo = null
                    extensionNotice = "Python extension removed"
                } else extensionNotice = "Could not remove Python extension"
            }
        }.start()
    }

    fun installRustFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Downloading Rust toolchain…"
        Thread {
            val installed = runCatching {
                val extension = extensionMarketplace.rustExtension()
                extensionMarketplace.downloadAndInstall(extension, extensionManager, context.cacheDir) { downloaded, total ->
                    postToMainThread {
                        extensionNotice = "Downloading Rust… ${if (total > 0) downloaded * 100 / total else 0}%"
                    }
                }
                extensionManager.installRustRuntime()
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess {
                    rustExtensionInfo = it
                    extensionNotice = "Installed ${it.name} ${it.version}"
                }.onFailure { extensionNotice = "Installation failed: ${it.message ?: "Server download failed"}" }
            }
        }.start()
    }

    fun uninstallRustExtension() {
        if (extensionBusy) return
        extensionBusy = true
        Thread {
            val removed = extensionManager.uninstallRust()
            postToMainThread {
                extensionBusy = false
                if (removed) {
                    rustExtensionInfo = null
                    extensionNotice = "Rust extension removed"
                } else extensionNotice = "Could not remove Rust extension"
            }
        }.start()
    }

    fun installGnuLanguagesFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Downloading Fortran & COBOL toolchain…"
        Thread {
            val installed = runCatching {
                val extension = extensionMarketplace.gnuLanguagesExtension()
                extensionMarketplace.downloadAndInstall(extension, extensionManager, context.cacheDir) { downloaded, total ->
                    postToMainThread {
                        extensionNotice = "Downloading Fortran & COBOL… ${if (total > 0) downloaded * 100 / total else 0}%"
                    }
                }
                extensionManager.installGnuLanguagesRuntime()
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess {
                    gnuLanguagesExtensionInfo = it
                    extensionNotice = "Installed ${it.name} ${it.version}"
                }.onFailure { extensionNotice = "Installation failed: ${it.message ?: "Server download failed"}" }
            }
        }.start()
    }

    fun uninstallGnuLanguagesExtension() {
        if (extensionBusy) return
        extensionBusy = true
        Thread {
            val removed = extensionManager.uninstallGnuLanguages()
            postToMainThread {
                extensionBusy = false
                if (removed) {
                    gnuLanguagesExtensionInfo = null
                    extensionNotice = "Fortran & COBOL extension removed"
                } else extensionNotice = "Could not remove Fortran & COBOL extension"
            }
        }.start()
    }

    fun installWebFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Downloading Web Development runtime…"
        Thread {
            val installed = runCatching {
                val extension = extensionMarketplace.webExtension()
                extensionMarketplace.downloadAndInstall(extension, extensionManager, context.cacheDir) { downloaded, total ->
                    postToMainThread {
                        extensionNotice = "Downloading Web Development… ${if (total > 0) downloaded * 100 / total else 0}%"
                    }
                }
                extensionManager.installWebRuntime()
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess {
                    webExtensionInfo = it
                    extensionNotice = "Installed ${it.name} ${it.version}"
                }.onFailure { extensionNotice = "Installation failed: ${it.message ?: "Server download failed"}" }
            }
        }.start()
    }

    fun uninstallWebExtension() {
        if (extensionBusy) return
        extensionBusy = true
        Thread {
            val removed = extensionManager.uninstallWeb()
            postToMainThread {
                extensionBusy = false
                if (removed) {
                    webExtensionInfo = null
                    extensionNotice = "Web Development extension removed"
                } else extensionNotice = "Could not remove Web Development extension"
            }
        }.start()
    }

    fun installGitToolsFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Downloading Git Tools…"
        Thread {
            val installed = runCatching {
                val extension = extensionMarketplace.gitExtension()
                extensionMarketplace.downloadAndInstall(extension, extensionManager, context.cacheDir) { downloaded, total ->
                    postToMainThread {
                        extensionNotice = "Downloading Git Tools… ${if (total > 0) downloaded * 100 / total else 0}%"
                    }
                }
                extensionManager.installGitRuntime()
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess {
                    gitToolsExtensionInfo = it
                    extensionNotice = "Installed ${it.name} ${it.version}"
                }.onFailure { extensionNotice = "Installation failed: ${it.message ?: "Server download failed"}" }
            }
        }.start()
    }

    fun uninstallGitToolsExtension() {
        if (extensionBusy) return
        extensionBusy = true
        Thread {
            val removed = extensionManager.uninstallGit()
            postToMainThread {
                extensionBusy = false
                if (removed) {
                    gitToolsExtensionInfo = null
                    extensionNotice = "Git Tools extension removed"
                } else extensionNotice = "Could not remove Git Tools extension"
            }
        }.start()
    }

    fun installGnuArmFromMarketplace() {
        if (extensionBusy) return
        if (!extensionMarketplace.configured) {
            extensionNotice = "Extension server is not configured in this build. Use Install from file."
            return
        }
        extensionBusy = true
        extensionNotice = "Downloading GNU Arm Embedded…"
        Thread {
            val installed = runCatching {
                val extension = extensionMarketplace.gnuArmExtension()
                extensionMarketplace.downloadAndInstall(extension, extensionManager, context.cacheDir) { downloaded, total ->
                    postToMainThread {
                        extensionNotice = "Downloading GNU Arm Embedded… ${if (total > 0) downloaded * 100 / total else 0}%"
                    }
                }
                extensionManager.installGnuArmRuntime()
            }
            postToMainThread {
                extensionBusy = false
                installed.onSuccess {
                    gnuArmExtensionInfo = it
                    extensionNotice = "Installed ${it.name} ${it.version}"
                }.onFailure { extensionNotice = "Installation failed: ${it.message ?: "Server download failed"}" }
            }
        }.start()
    }

    fun uninstallGnuArmExtension() {
        if (extensionBusy) return
        extensionBusy = true
        Thread {
            val removed = extensionManager.uninstallGnuArm()
            postToMainThread {
                extensionBusy = false
                if (removed) {
                    gnuArmExtensionInfo = null
                    extensionNotice = "GNU Arm Embedded extension removed"
                } else extensionNotice = "Could not remove GNU Arm Embedded extension"
            }
        }.start()
    }

    fun uninstallPicoExtension() {
        if (extensionBusy) return
        extensionBusy = true
        extensionNotice = "Removing Raspberry Pi Pico extension…"
        Thread {
            val removed = extensionManager.uninstallPico()
            postToMainThread {
                extensionBusy = false
                if (removed) {
                    picoExtensionInfo = null
                    extensionNotice = "Raspberry Pi Pico extension removed"
                } else {
                    extensionNotice = "Could not remove Raspberry Pi Pico extension"
                }
            }
        }.start()
    }

    fun uninstallCppExtension() {
        if (extensionBusy) return
        extensionBusy = true
        extensionNotice = "Removing C/C++ extension…"
        Thread {
            val removed = runCatching { extensionManager.uninstallCpp() }
            postToMainThread {
                extensionBusy = false
                removed.onSuccess {
                    if (it) cppExtensionInfo = null
                    extensionNotice = if (it) "C/C++ extension removed" else "Could not remove C/C++ extension"
                }.onFailure { extensionNotice = "Removal failed: ${it.message}" }
            }
        }.start()
    }

    fun preparePicoComponents(componentIds: Set<String>) {
        if (extensionBusy || picoExtensionInfo == null) return
        extensionBusy = true
        extensionNotice = "Preparing selected offline components…"
        Thread {
            val prepared = runCatching {
                val missing = extensionManager.missingPayloads("dev.foldcode.pico", componentIds)
                if (missing.isNotEmpty()) {
                    require(extensionMarketplace.configured) { "Connect to the extension server to download this component" }
                    val extension = extensionMarketplace.picoExtension()
                    extensionMarketplace.downloadPayloads(extension, missing, extensionManager, context.cacheDir) { name, downloaded, total ->
                        postToMainThread {
                            extensionNotice = "Downloading $name… ${if (total > 0) downloaded * 100 / total else 0}%"
                        }
                    }
                }
                extensionManager.preparePicoComponents(componentIds) { name, completed, total ->
                    postToMainThread {
                        extensionNotice = "Offline pack $completed/$total · $name"
                    }
                }
            }
            postToMainThread {
                extensionBusy = false
                prepared.onSuccess { info ->
                    picoExtensionInfo = info
                    extensionNotice = if (info.offlineReady) "Complete Pico offline pack is ready" else "Selected components are ready offline"
                }.onFailure {
                    picoExtensionInfo = extensionManager.picoInfo()
                    extensionNotice = "Offline preparation failed"
                }
            }
        }.start()
    }

    fun deletePicoComponents(componentIds: Set<String>) {
        if (extensionBusy || picoExtensionInfo == null || componentIds.isEmpty()) return
        extensionBusy = true
        extensionNotice = "Removing selected components…"
        Thread {
            val removed = runCatching { extensionManager.deletePicoComponents(componentIds) }
            postToMainThread {
                extensionBusy = false
                removed.onSuccess { info ->
                    picoExtensionInfo = info
                    extensionNotice = "Selected components were removed"
                }.onFailure { error ->
                    picoExtensionInfo = extensionManager.picoInfo()
                    extensionNotice = "Component removal failed: ${error.message ?: "Unknown error"}"
                }
            }
        }.start()
    }

}

/** Components carried by a local package should be ready after that package is installed. */
internal fun bundledPicoComponentIds(info: FoldCodeExtensionInfo): Set<String> {
    require(info.id == "dev.foldcode.pico") { "Expected the Raspberry Pi Pico extension" }
    return info.components
        .filter(FoldCodeExtensionComponent::availableOffline)
        .mapTo(linkedSetOf(), FoldCodeExtensionComponent::id)
        .also { require(it.isNotEmpty()) { "Pico extension contains no installable components" } }
}

@Composable
internal fun rememberWorkspaceExtensions(
    context: Context,
    extensionManager: FoldCodeExtensionManager,
    extensionMarketplace: FoldCodeExtensionMarketplace,
): WorkspaceExtensionController {
    val applicationContext = context.applicationContext
    val controller = remember(applicationContext, extensionManager, extensionMarketplace) {
        // Installation can outlive an Activity while a large local package is
        // extracted. Never let that background operation retain the Activity.
        WorkspaceExtensionController(applicationContext, extensionManager, extensionMarketplace)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        controller.installFromUri(it)
    }
    controller.bindInstallerLauncher {
        launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }
    return controller
}
