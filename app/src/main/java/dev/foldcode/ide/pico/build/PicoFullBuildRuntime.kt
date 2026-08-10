package dev.foldcode.ide

import android.content.Context
import java.io.File

/** Stable API implemented by the small native runtime included in the base app. */
data class PicoFullBuildRequest(
    val projectDirectory: String,
    val boardId: String,
    val platform: String,
    val target: String?,
    val sdkDirectory: String,
    val compilerHeadersDirectory: String,
    val compilerRuntimeDirectory: String,
    val dependencyNetworkPolicy: String,
    val dependencySourceDirectories: Map<String, String> = emptyMap(),
    val configureOnly: Boolean = false,
    val cleanOnly: Boolean = false,
    val parallelJobs: Int = 0,
    val thermalAware: Boolean = true,
    val debugBuild: Boolean = false,
    val buildType: String = PicoBuildType.Release.cmakeValue,
)

data class PicoFullBuildResult(
    val succeeded: Boolean,
    val output: String,
    val artifacts: List<String> = emptyList(),
)

interface PicoFullBuildRuntime {
    val version: String
    fun probe(): String
    fun build(request: PicoFullBuildRequest, onProgress: (String) -> Unit = {}): PicoFullBuildResult
    fun cancel()
}

/** Base-app runtime host. Only its large SDK/toolchain data is downloaded as an extension. */
internal class PicoRuntimeDelivery(private val context: Context) {
    private val runtime: PicoFullBuildRuntime? by lazy {
        runCatching { PicoFullBuildRuntimeImpl(context.applicationContext) }.getOrNull()
    }

    fun runtimeOrNull(): PicoFullBuildRuntime? = runtime

    fun buildRequest(
        projectDirectory: File,
        configuration: PicoProjectConfiguration,
        sdkDirectory: File,
        compilerHeadersDirectory: File,
        compilerRuntimeDirectory: File,
        dependencySourceDirectories: Map<String, String> = emptyMap(),
        configureOnly: Boolean = false,
        cleanOnly: Boolean = false,
        parallelJobs: Int = 0,
        thermalAware: Boolean = true,
        debugBuild: Boolean = false,
    ) = PicoFullBuildRequest(
        projectDirectory = projectDirectory.absolutePath,
        boardId = configuration.board.boardId,
        platform = configuration.board.platform,
        target = configuration.target,
        sdkDirectory = sdkDirectory.absolutePath,
        compilerHeadersDirectory = compilerHeadersDirectory.absolutePath,
        compilerRuntimeDirectory = compilerRuntimeDirectory.absolutePath,
        dependencyNetworkPolicy = configuration.networkPolicy.markerValue,
        dependencySourceDirectories = dependencySourceDirectories,
        configureOnly = configureOnly,
        cleanOnly = cleanOnly,
        parallelJobs = parallelJobs,
        thermalAware = thermalAware,
        debugBuild = debugBuild,
        buildType = configuration.buildType.cmakeValue,
    )

}
