package dev.foldcode.ide

import java.io.File
import java.security.MessageDigest

private enum class CompatibilityPatch { Missing, Current, Applied }

/**
 * Preserve Cargo's generated lockfile when the editor model was loaded before
 * the first Cargo invocation created it.
 */
internal fun rustProjectSourcesForSave(
    project: File,
    editorSources: Map<String, String>,
): Map<String, String> {
    if ("Cargo.lock" in editorSources) return editorSources
    val lock = File(project, "Cargo.lock")
    val content = lock.takeIf(File::isFile)?.runCatching(File::readText)?.getOrNull()
        ?: return editorSources
    return editorSources + ("Cargo.lock" to content)
}

/**
 * Makes Cargo's project-local dependency cache usable by both foreground builds
 * and background Rust diagnostics. A content marker prevents network access on
 * later edits while still invalidating when Cargo inputs or the Pico target change.
 */
internal fun prepareRustCargoCompatibility(
    project: File,
    target: String,
    cargoTargetDirectory: File,
    output: StringBuilder,
    onProgress: (String) -> Unit,
    fetchCargo: (arguments: List<String>, output: (String) -> Unit) -> Boolean,
): Boolean {
    val dependencyMarker = rustCargoDependencyMarker(project, target)
    val expectedFingerprint = rustCargoDependencyFingerprint(project, target)
    var compatibilityApplied = false
    when (patchProcMacroError2(project)) {
        CompatibilityPatch.Applied -> compatibilityApplied = true
        CompatibilityPatch.Current, CompatibilityPatch.Missing -> Unit
    }

    if (dependencyMarker.takeIf(File::isFile)?.runCatching { readText() }?.getOrNull() == expectedFingerprint) {
        return !compatibilityApplied || invalidateProcMacroError2(
            project,
            cargoTargetDirectory,
            output,
            onProgress,
        )
    }

    onProgress("Preparing Cargo dependencies for editor analysis…")
    val fetchOutput = StringBuilder()
    val lockArgument = if (File(project, "Cargo.lock").isFile) listOf("--locked") else emptyList()
    val fetched = fetchCargo(listOf("fetch", "--target", target) + lockArgument) { line ->
        fetchOutput.appendLine(line)
        onProgress(line)
    }
    if (!fetched) {
        output.append(fetchOutput.toString().trimEnd())
        return false
    }

    when (patchProcMacroError2(project)) {
        CompatibilityPatch.Applied -> compatibilityApplied = true
        CompatibilityPatch.Current, CompatibilityPatch.Missing -> Unit
    }
    if (compatibilityApplied && !invalidateProcMacroError2(
            project,
            cargoTargetDirectory,
            output,
            onProgress,
        )
    ) return false

    return runCatching {
        dependencyMarker.parentFile?.mkdirs()
        dependencyMarker.writeText(rustCargoDependencyFingerprint(project, target))
        true
    }.getOrDefault(false)
}

private fun rustCargoDependencyMarker(project: File, target: String): File {
    val safeTarget = target.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return File(project, ".foldcode/compat/cargo-dependencies-$safeTarget-v1")
}

private fun rustCargoDependencyFingerprint(project: File, target: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(target.toByteArray())
    listOf(
        File(project, "Cargo.toml"),
        File(project, "Cargo.lock"),
        File(project, ".cargo/config.toml"),
        File(project, ".cargo/config"),
    ).forEach { input ->
        digest.update(0.toByte())
        digest.update(input.name.toByteArray())
        if (input.isFile) input.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * rustc 1.97 rejects proc-macro-error2 2.0.1's private extern-crate re-export.
 * Patch only that verified upstream statement in the project-local Cargo cache.
 */
private fun patchProcMacroError2(project: File): CompatibilityPatch {
    val registrySources = File(project, ".foldcode/cargo/registry/src")
    val candidates = registrySources.listFiles().orEmpty().map {
        File(it, "proc-macro-error2-2.0.1/src/lib.rs")
    }.filter(File::isFile)
    if (candidates.isEmpty()) return CompatibilityPatch.Missing

    val privateImport = "extern crate proc_macro;"
    val publicImport = "pub extern crate proc_macro;"
    var sourceChanged = false
    candidates.forEach { source ->
        val content = runCatching { source.readText() }.getOrNull() ?: return@forEach
        if (publicImport in content) return@forEach
        val importOffset = content.indexOf(privateImport)
        val reExportOffset = content.indexOf("pub use proc_macro;", startIndex = importOffset.coerceAtLeast(0))
        if (importOffset < 0 || reExportOffset < 0) return@forEach
        val patched = content.replaceRange(
            importOffset,
            importOffset + privateImport.length,
            publicImport,
        )
        runCatching { source.writeText(patched) }.getOrElse { return CompatibilityPatch.Missing }
        sourceChanged = true
    }
    val valid = candidates.all { source ->
        runCatching { publicImport in source.readText() }.getOrDefault(false)
    }
    if (!valid) return CompatibilityPatch.Missing
    val marker = File(project, ".foldcode/compat/proc-macro-error2-2.0.1-rust-1.97-r2")
    return if (sourceChanged || !marker.isFile) CompatibilityPatch.Applied else CompatibilityPatch.Current
}

private fun invalidateProcMacroError2(
    project: File,
    cargoTargetDirectory: File,
    output: StringBuilder,
    onProgress: (String) -> Unit,
): Boolean {
    onProgress("Applying Rust compatibility fix for proc-macro-error2 2.0.1…")
    val removed = runCatching {
        listOf("debug", "release").forEach { profileName ->
            val profile = File(cargoTargetDirectory, profileName)
            File(profile, ".fingerprint").listFiles().orEmpty()
                .filter { it.name.startsWith("proc-macro-error2-") }
                .forEach { require(it.deleteRecursively()) { "Could not remove ${it.name}" } }
            File(profile, "deps").listFiles().orEmpty()
                .filter { it.name.contains("proc_macro_error2-") }
                .forEach { require(it.delete()) { "Could not remove ${it.name}" } }
        }
        val report = File(cargoTargetDirectory, ".future-incompat-report.json")
        require(!report.exists() || report.delete()) { "Could not remove the stale Cargo report" }
        true
    }.getOrElse { error ->
        output.append("Could not invalidate proc-macro-error2: ${error.message}")
        false
    }
    if (!removed) return false

    val marker = File(project, ".foldcode/compat/proc-macro-error2-2.0.1-rust-1.97-r2")
    return runCatching {
        marker.parentFile?.mkdirs()
        marker.writeText("Patched private proc_macro re-export for rustc 1.97.\n")
        true
    }.getOrDefault(false)
}
