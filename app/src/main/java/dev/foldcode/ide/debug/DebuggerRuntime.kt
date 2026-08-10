package dev.foldcode.ide

import android.content.Context
import java.io.File

/** Resolves debugger code delivered by language/hardware extensions. */
internal class DebuggerRuntime(private val context: Context) {
    private val cppRoot get() = File(context.filesDir, "cpp-runtime")
    private val picoRoot get() = File(context.filesDir, "pico-tools-runtime")

    val lldbDapLauncher: File get() = File(context.applicationInfo.nativeLibraryDir, "foldlldbdap.so")
    val debuggeeLauncher: File get() = File(context.applicationInfo.nativeLibraryDir, "folddebuggee.so")
    val lldbServer: File get() = File(context.applicationInfo.nativeLibraryDir, "foldlldbserver.so")
    val lldbCore: File get() = File(cppRoot, "native/libfoldlldbdapcore.so")
    val openOcdLauncher: File get() = File(context.applicationInfo.nativeLibraryDir, "foldopenocd.so")
    val openOcdExecutable: File get() = File(picoRoot, "debug/bin/openocd")
    val openOcdScripts: File get() = File(picoRoot, "debug/share/openocd/scripts")

    val hostAvailable: Boolean
        get() = lldbDapLauncher.canExecute() && debuggeeLauncher.canExecute() &&
            lldbServer.canExecute() && lldbCore.isFile

    val picoAvailable: Boolean
        get() = hostAvailable && openOcdLauncher.canExecute() && openOcdExecutable.isFile && openOcdScripts.isDirectory

    fun missingHostMessage(): String = when {
        !File(context.filesDir, "extensions/dev.foldcode.cpp/manifest.json").isFile ->
            "Install the C/C++ extension to add LLDB."
        !lldbCore.isFile -> "Install the LLDB debugger component from the C/C++ extension details."
        !lldbDapLauncher.canExecute() -> "This FoldCode APK does not contain the LLDB adapter host. Update FoldCode."
        !debuggeeLauncher.canExecute() -> "This FoldCode APK does not contain the debug target launcher. Update FoldCode."
        !lldbServer.canExecute() -> "This FoldCode APK does not contain the Android debug server. Update FoldCode."
        else -> "LLDB is unavailable."
    }

    fun missingPicoMessage(): String = when {
        !hostAvailable -> missingHostMessage()
        !openOcdExecutable.isFile -> "Install the SWD / OpenOCD component from the Raspberry Pi Pico extension details."
        !openOcdLauncher.canExecute() -> "This FoldCode APK does not contain the OpenOCD host. Update FoldCode."
        else -> "Pico SWD support is unavailable."
    }
}
