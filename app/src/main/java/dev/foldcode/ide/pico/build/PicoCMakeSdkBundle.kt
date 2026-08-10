package dev.foldcode.ide

import android.content.Context
import java.io.File

/** Materializes the full official SDK and CMake's data files from the Pico extension. */
internal class PicoCMakeSdkBundle(private val context: Context) {
    val sdkRoot = File(context.filesDir, "pico-cmake-sdk/sdk")
    val cmakePrefix = File(context.filesDir, "usr")

    @Synchronized
    fun install() {
        val extension = FoldCodeExtensionManager(context)
        extension.materializePicoPayload("pico-cmake-sdk.zip", sdkRoot.parentFile!!)
        extension.materializePicoPayload("cmake-data.zip", cmakePrefix)
        require(File(sdkRoot, "pico_sdk_init.cmake").isFile) { "Full Pico SDK payload is incomplete" }
        require(File(cmakePrefix, "share/cmake-4.0/Modules/CMake.cmake").isFile) {
            "CMake data payload is incomplete"
        }
        patchBootPad(File(sdkRoot, "src/rp2040/boot_stage2/CMakeLists.txt"))
        patchBootPad(File(sdkRoot, "src/rp2350/boot_stage2/CMakeLists.txt"))
        // FoldCode ships the compact GNU multi-architecture objdump. The SDK's
        // Clang profiles add LLVM-only --mcpu/--arch spelling; ELF metadata is
        // sufficient for GNU objdump to select the correct disassembler.
        patchClangObjdump(File(sdkRoot, "cmake/preload/toolchains/pico_arm_cortex_m0plus_clang.cmake"))
        patchClangObjdump(File(sdkRoot, "cmake/preload/toolchains/pico_arm_cortex_m33_clang.cmake"))
    }

    private fun patchBootPad(file: File) {
        val original = file.readText()
        val patched = original.replace(
            "find_package (Python3 REQUIRED COMPONENTS Interpreter)",
            "if(NOT FOLDCODE_PICO_BOOT_PAD)\n" +
                "    message(FATAL_ERROR \"FoldCode boot-pad helper is unavailable\")\n" +
                "endif()\n" +
                "set(Python3_EXECUTABLE \"${'$'}{FOLDCODE_PICO_BOOT_PAD}\")",
        )
        if (patched != original) file.writeText(patched)
    }

    private fun patchClangObjdump(file: File) {
        val original = file.readText()
        val patched = original.replace(
            Regex("set\\(PICO_DISASM_OBJDUMP_ARGS[^\\n]*\\)"),
            "set(PICO_DISASM_OBJDUMP_ARGS)",
        )
        if (patched != original) file.writeText(patched)
    }
}
