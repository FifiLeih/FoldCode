package dev.foldcode.ide

import android.content.Context
import java.io.File

private val rustRuntimeInstallLock = Any()

/** Materialize the locally installed Rust extension exactly once across editor/build workers. */
internal fun ensureRustRuntimeInstalled(context: Context): File = synchronized(rustRuntimeInstallLock) {
    FoldCodeExtensionManager(context).installRustRuntime()
    File(context.filesDir, "rust-runtime")
}

/**
 * Cargo's build scripts and procedural macros are Android-host executables even
 * when the final crate targets a Pico. Their debug symbols can make the glibc LLD
 * bridge unstable under PRoot, while providing no firmware debugging value.
 */
internal fun configureRustCargoHostBuildEnvironment(environment: MutableMap<String, String>) {
    environment["CARGO_PROFILE_DEV_BUILD_OVERRIDE_DEBUG"] = "0"
    environment["CARGO_PROFILE_RELEASE_BUILD_OVERRIDE_DEBUG"] = "0"
}

internal fun isRustHostCompilerCrash(output: CharSequence): Boolean =
    output.contains("rustc interrupted by SIGSEGV", ignoreCase = true)

internal fun isRustHostLinkerCrash(output: CharSequence): Boolean =
    output.contains("linking with", ignoreCase = true) &&
        output.contains("/usr/bin/cc", ignoreCase = true) &&
        output.contains("SIGSEGV", ignoreCase = true)
