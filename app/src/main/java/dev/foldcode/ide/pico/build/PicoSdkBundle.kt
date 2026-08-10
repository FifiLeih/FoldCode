package dev.foldcode.ide

import android.content.Context
import java.io.File

/** Compiler headers and GNU runtime libraries used by the official Pico CMake build. */
internal class PicoSdkBundle(private val context: Context, private val board: PicoBoard) {
    val root = File(context.filesDir, "pico-sdk-${board.bundleId}")
    private val toolchainRoot = File(context.filesDir, if (board.chip.riscV) "pico-toolchain-riscv" else "pico-toolchain-arm")
    val compilerHeadersDirectory get() = File(toolchainRoot, "toolchain/include")
    val cmakeCompilerHeadersDirectory get() = listOf(
        File(toolchainRoot, "toolchain/sysroot/include"),
        compilerHeadersDirectory,
    ).firstOrNull(::hasRequiredCompilerHeaders)
        ?: File(toolchainRoot, "toolchain/sysroot/include")
    val sdkVersion get() = PicoSdkRelease.sdkVersion
    val runtimeDirectory get() = File(root, "runtime")

    private val version = "${PicoSdkRelease.installId}-${board.bundleId}"

    @Synchronized
    fun install() {
        val marker = File(root, ".version")
        if (marker.takeIf(File::isFile)?.readText() == version && hasCompleteToolchain()) return
        val extensions = FoldCodeExtensionManager(context)
        extensions.materializePicoPayload(
            if (board.chip.riscV) "pico-toolchain-riscv.zip" else "pico-toolchain-arm.zip",
            toolchainRoot,
        )
        extensions.materializePicoPayload("pico-profile-${board.bundleId}.zip", root)
        val bundleProperties = File(root, "bundle.properties").readLines()
            .mapNotNull { line -> line.substringBefore('=', "").takeIf(String::isNotBlank)?.let { it to line.substringAfter('=') } }
            .toMap()
        require(bundleProperties["sdk.version"] == PicoSdkRelease.sdkVersion) { "Pico SDK asset version mismatch" }
        val expectedGcc = if (board.chip.riscV) PicoSdkRelease.riscVGccVersion else PicoSdkRelease.gccVersion
        require(bundleProperties["gcc.version"] == expectedGcc) { "Pico GNU runtime version mismatch" }
        require(bundleProperties["platform"] == board.platform) { "Pico platform asset mismatch" }
        require(hasCompleteToolchain()) {
            "Pico compiler component is incomplete; update the Pico extension and download this board component again"
        }
        require(runtimeLibraries().all(File::isFile)) { "Pico SDK asset is missing GNU runtime libraries" }
        marker.writeText(version)
    }

    private fun hasCompleteToolchain(): Boolean {
        return hasRequiredCompilerHeaders(cmakeCompilerHeadersDirectory)
    }

    private fun hasRequiredCompilerHeaders(headers: File): Boolean =
        headers.isDirectory &&
            File(headers, "assert.h").isFile &&
            File(headers, "stdint.h").isFile

    fun runtimeLibraries(): List<File> = listOf("libstdc++.a", "libc.a", "libm.a", "libgcc.a", "libnosys.a")
        .map { File(runtimeDirectory, it) }
}
