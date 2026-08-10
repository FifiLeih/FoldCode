package dev.foldcode.ide

import android.content.Context
import java.io.File

internal class BundledToolchain(private val context: Context) {
    private val runtimeRoot = File(context.filesDir, "cpp-runtime")
    val root = File(runtimeRoot, "toolchain")
    val nativeDependencies = File(runtimeRoot, "native")
    // Small executable launchers remain APK-labelled. Their large LLVM/Clang
    // dependencies are supplied by the server-delivered C/C++ extension.
    val clang = File(context.applicationInfo.nativeLibraryDir, "libfoldclang.so")
    val clangd = File(context.applicationInfo.nativeLibraryDir, "foldclangd.so")
    val clangdCore = File(nativeDependencies, "libfoldclangdcore.so")
    val lld = File(context.applicationInfo.nativeLibraryDir, "foldlld.so")
    val gnuLd = File(context.applicationInfo.nativeLibraryDir, "libfoldld.so")
    val pioasm = File(context.applicationInfo.nativeLibraryDir, "foldpioasm.so")
    val resourceDir = File(root, "lib/clang/21")
    val sysroot = File(root, "sysroot")

    val hasLanguageServer: Boolean
        get() = clangd.canExecute() && clangdCore.isFile

    @Synchronized
    fun install() {
        FoldCodeExtensionManager(context).installCppRuntime()
        require(File(nativeDependencies, "libLLVM.so").isFile && resourceDir.isDirectory && sysroot.isDirectory) {
            "The C/C++ compiler extension runtime is incomplete"
        }
    }
}
