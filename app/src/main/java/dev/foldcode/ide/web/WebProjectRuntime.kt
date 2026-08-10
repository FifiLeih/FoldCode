package dev.foldcode.ide

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Runs web tooling from app-private storage.
 *
 * FoldCode projects intentionally live in shared storage so users can open and
 * copy them. Android's shared-storage filesystem cannot create the symbolic
 * links used by npm and cannot execute native package helpers such as esbuild.
 * This mirror keeps source authoritative in FoldCode/Projects while retaining
 * generated node_modules in an executable, project-scoped private directory.
 */
internal class WebProjectRuntime(context: Context) {
    private val root = File(context.filesDir, "web-projects").apply { mkdirs() }

    data class PreparedProject(
        val directory: File,
        val installRequired: Boolean,
        val dependencyFingerprint: String,
        val nodeLauncher: File,
    ) {
        fun startCommand(): String {
            val install = if (installRequired) {
                // npm's detached lifecycle-script runner is unreliable in an
                // Android app process. Foreground mode keeps native installers
                // such as esbuild attached to the working Node runtime. Keep
                // routine npm notices out of the project terminal, but retain
                // lifecycle output and actual errors when installation fails.
                "printf 'Installing web dependencies…\\n' && " +
                    "npm install --foreground-scripts --loglevel=error --no-audit --no-fund && " +
                    "printf '%s' '$dependencyFingerprint' > .foldcode-dependencies && "
            } else ""
            val quotedDirectory = "'${directory.absolutePath.replace("'", "'\\''")}'"
            // Vite 8 uses the WASI build of Rolldown on Android because npm's
            // desktop native bindings cannot execute in an app sandbox. Node
            // still labels its WASI host API experimental, so suppress runtime
            // bootstrap warnings only for this web process. Project/compiler
            // diagnostics printed by Vite remain visible.
            return "(cd $quotedDirectory && " +
                "export NODE_NO_WARNINGS=1 NO_COLOR=1 FORCE_COLOR=0; " +
                "${install}${repairNodeLaunchers()} && " +
                "printf 'Starting web server…\\n' && npm start --silent)"
        }

        fun npmCommand(
            arguments: String,
            sourceDirectory: File,
            syncProjectManifests: Boolean,
        ): String {
            val quotedDirectory = shellQuote(directory.absolutePath)
            val quotedSource = shellQuote(sourceDirectory.absolutePath)
            val command = if (arguments.isBlank()) "npm" else "npm $arguments"
            return buildString {
                append("(cd ").append(quotedDirectory).append(" && ")
                append(command).append("; foldcode_status=\$?; ")
                if (syncProjectManifests) {
                    append("if [ \$foldcode_status -eq 0 ]; then ")
                    append(repairNodeLaunchers()).append("; ")
                    // Dependency commands update the authoritative project
                    // manifests while node_modules remains private and executable.
                    append("for foldcode_manifest in package.json package-lock.json npm-shrinkwrap.json; do ")
                    append("if [ -f \"\$foldcode_manifest\" ]; then cp \"\$foldcode_manifest\" ")
                    append(quotedSource).append("/\"\$foldcode_manifest\"; fi; done; ")
                    append("fi; ")
                }
                append("exit \$foldcode_status)")
            }
        }

        /**
         * npm generates executable JavaScript files with `#!/usr/bin/env node`.
         * Android has /system/bin/env but deliberately has no /usr/bin/env, so
         * otherwise every package command (vite, tsc, eslint, …) fails with a
         * misleading "No such file or directory" message. Repair only the
         * first line of executable package launchers in the private mirror.
         */
        private fun repairNodeLaunchers(): String {
            val launcher = nodeLauncher.absolutePath.replace("'", "'\\''")
            val launchers = "find node_modules -type f -perm /111 -exec " +
                "sed -i '1s|^#!/usr/bin/env node$|#!$launcher|' {} \\;"
            // @rolldown/browser's generated WASI loaders preopen the filesystem
            // root by default. Android apps cannot open `/`, so uvwasi_init
            // fails with EACCES and Vite exits before its logger is ready. Web
            // builds only need their private project mirror; scope both the
            // main loader and its worker to the current project directory.
            val wasiPreopens = "for foldcode_wasi in " +
                "node_modules/rolldown/dist/rolldown-binding.wasi.cjs " +
                "node_modules/rolldown/dist/wasi-worker.mjs; do " +
                "if [ -f \"\$foldcode_wasi\" ]; then sed -i " +
                "-e 's|const __rootDir = __nodePath.parse(process.cwd()).root|const __rootDir = process.cwd()|' " +
                "-e 's|const __rootDir = parse(process.cwd()).root;|const __rootDir = process.cwd();|' " +
                "\"\$foldcode_wasi\"; fi; done"
            // Use an explicit conditional so this helper remains one successful
            // command in a surrounding `&&` chain. A bare `test || find` can
            // bind to the preceding npm install and run `find` after npm fails.
            return "if [ -d node_modules ]; then $launchers ; $wasiPreopens ; fi"
        }


        private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }

    @Synchronized
    fun prepare(projectKey: String, sourceDirectory: File): PreparedProject {
        require(sourceDirectory.isDirectory) { "Web project directory is unavailable" }
        // Key mirrors by their real source directory so Run and commands typed
        // into the project terminal always operate on the same dependency tree.
        val directory = runtimeDirectory(sourceDirectory).apply { mkdirs() }

        // Remove the previous source mirror but preserve downloaded packages.
        directory.listFiles().orEmpty().forEach { child ->
            if (child.name !in PRESERVED_RUNTIME_ENTRIES) child.deleteRecursively()
        }
        copyProject(sourceDirectory, directory)
        applyRuntimeCompatibility(directory)

        val fingerprint = runtimeFingerprint(sourceDirectory)
        val installedFingerprint = File(directory, ".foldcode-dependencies")
            .takeIf(File::isFile)?.readText()?.trim()
        if (installedFingerprint != fingerprint) {
            // npm can roll back only part of a failed platform-specific update,
            // leaving (for example) esbuild's JavaScript package and Android
            // binary at different versions. Dependency changes get a clean
            // private install; shared project sources remain untouched.
            File(directory, "node_modules").deleteRecursively()
        }
        val installRequired = !File(directory, "node_modules").isDirectory || installedFingerprint != fingerprint
        return PreparedProject(
            directory = directory,
            installRequired = installRequired,
            dependencyFingerprint = fingerprint,
            nodeLauncher = File(root.parentFile, "terminal-bin/node"),
        )
    }

    /**
     * Ordinary npm queries must not rewrite a mirror underneath a running Vite
     * process. Commands which intentionally change project/package state still
     * receive a freshly synchronized source tree when no server owns it.
     */
    @Synchronized
    fun prepareForTerminalCommand(
        projectKey: String,
        sourceDirectory: File,
        refreshProject: Boolean,
    ): PreparedProject {
        val directory = runtimeDirectory(sourceDirectory)
        if (refreshProject || !File(directory, "package.json").isFile) {
            return prepare(projectKey, sourceDirectory)
        }
        val fingerprint = runtimeFingerprint(sourceDirectory)
        val installedFingerprint = File(directory, ".foldcode-dependencies")
            .takeIf(File::isFile)?.readText()?.trim()
        return PreparedProject(
            directory = directory,
            installRequired = !File(directory, "node_modules").isDirectory ||
                installedFingerprint != fingerprint,
            dependencyFingerprint = fingerprint,
            nodeLauncher = File(root.parentFile, "terminal-bin/node"),
        )
    }

    /**
     * TypeScript must share the executable private mirror used by npm. The
     * public project deliberately has no node_modules because Android shared
     * storage cannot execute npm launchers or represent all package symlinks.
     */
    @Synchronized
    fun intelligenceDirectory(sourceDirectory: File): File {
        if (!sourceDirectory.isDirectory) return sourceDirectory
        val mirror = runtimeDirectory(sourceDirectory)
        return selectWebIntelligenceDirectory(
            sourceDirectory = sourceDirectory,
            runtimeDirectory = mirror,
            expectedFingerprint = runtimeFingerprint(sourceDirectory),
        )
    }

    /**
     * Keep a running Vite project's private Android-compatible mirror in sync
     * with files saved by the editor. Vite watches this mirror, rather than the
     * public Documents directory, so updating it is what triggers HMR/reload.
     */
    @Synchronized
    fun syncSavedFiles(sourceDirectory: File, relativePaths: Collection<String>) {
        if (!sourceDirectory.isDirectory) return
        val directory = runtimeDirectory(sourceDirectory)
        if (!directory.isDirectory) return

        relativePaths.forEach { rawPath ->
            val relativePath = rawPath.replace('\\', '/').trimStart('/')
            val parts = relativePath.split('/').filter(String::isNotEmpty)
            if (parts.isEmpty() || parts.any { it == ".." }) return@forEach
            if (parts.first() in EXCLUDED_SOURCE_ENTRIES) return@forEach
            if (relativePath in LIVE_SYNC_EXCLUDED_ROOT_FILES) return@forEach

            val source = File(sourceDirectory, relativePath)
            val target = File(directory, relativePath)
            if (source.isFile) {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                // Some file watchers use timestamp granularity coarser than the
                // rapid autosave interval, so force an observable modification.
                target.setLastModified(System.currentTimeMillis())
            } else if (!source.exists()) {
                target.deleteRecursively()
            }
        }
    }

    /** Wait for the local development server before creating a WebView tab. */
    fun waitUntilReady(url: String, timeoutMillis: Long = 45_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val ready = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 600
                    connection.readTimeout = 600
                    connection.requestMethod = "GET"
                    connection.responseCode in 200..499
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (ready) return true
            Thread.sleep(250)
        }
        return false
    }

    /** Keep FoldCode templates current without rewriting the user's project.
     * Android cannot execute npm-downloaded native helpers, so Vite's native
     * Rolldown dependency is replaced by its official WebAssembly build in the
     * private runtime mirror.
     */
    private fun applyRuntimeCompatibility(directory: File) {
        val manifest = File(directory, "package.json").takeIf(File::isFile) ?: return
        val original = runCatching { manifest.readText() }.getOrNull() ?: return
        val packageJson = runCatching { JSONObject(original) }.getOrNull() ?: return
        if (packageJson.optString("name") !in FOLDCODE_TEMPLATE_NAMES) return
        val development = packageJson.optJSONObject("devDependencies") ?: JSONObject().also {
            packageJson.put("devDependencies", it)
        }
        development.put("typescript", "^5.9.3")
        development.put("vite", "^8.2.0")
        if (packageJson.optString("name") == "foldcode-react-app") {
            val dependencies = packageJson.optJSONObject("dependencies") ?: JSONObject().also {
                packageJson.put("dependencies", it)
            }
            dependencies.put("react", "^19.2.8")
            dependencies.put("react-dom", "^19.2.8")
            development.put("@types/react", "^19.2.18")
            development.put("@types/react-dom", "^19.2.4")
            development.put("@vitejs/plugin-react", "^6.0.5")
        }
        packageJson.optJSONObject("scripts")?.let { scripts ->
            scripts.keys().forEach { name ->
                val command = scripts.optString(name)
                    .replace(
                        Regex("(?<![A-Za-z0-9_./-])vite(?=\\s|$)"),
                        "node node_modules/vite/bin/vite.js",
                    )
                    .replace(
                        Regex("(?<![A-Za-z0-9_./-])tsc(?=\\s|$)"),
                        "node node_modules/typescript/bin/tsc",
                    )
                scripts.put(name, command)
            }
        }
        // Android does not permit the app sandbox to execute native binaries
        // downloaded into app data. The official WebAssembly distributions
        // provide the same JavaScript APIs without violating that boundary.
        val overrides = packageJson.optJSONObject("overrides") ?: JSONObject().also {
            packageJson.put("overrides", it)
        }
        overrides.put("rolldown", "npm:@rolldown/browser@1.2.1")
        overrides.put("esbuild", "npm:esbuild-wasm@0.28.1")
        overrides.put("rollup", "npm:@rollup/wasm-node@4.62.4")
        val compatible = packageJson.toString(2) + "\n"
        if (compatible == original) return
        manifest.writeText(compatible)
        // An older lock file would override the compatible private manifest.
        File(directory, "package-lock.json").delete()
        File(directory, "npm-shrinkwrap.json").delete()
    }

    private fun copyProject(source: File, destination: File) {
        source.listFiles().orEmpty().forEach { entry ->
            if (entry.name in EXCLUDED_SOURCE_ENTRIES) return@forEach
            val target = File(destination, entry.name)
            if (entry.isDirectory) {
                target.mkdirs()
                copyProject(entry, target)
            } else if (entry.isFile) {
                target.parentFile?.mkdirs()
                entry.inputStream().use { input -> target.outputStream().use(input::copyTo) }
                target.setLastModified(entry.lastModified())
            }
        }
    }

    private fun dependencyFingerprint(source: File): String = sha256(
        listOf("package.json", "package-lock.json", "npm-shrinkwrap.json")
            .map { name -> File(source, name) }
            .filter(File::isFile)
            .joinToString("\u0000") { file -> "${file.name}\u0000${file.readText()}" },
    )

    private fun runtimeFingerprint(source: File): String =
        dependencyFingerprint(source) + RUNTIME_COMPATIBILITY_VERSION

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun runtimeDirectory(sourceDirectory: File): File =
        File(root, sha256(sourceDirectory.canonicalPath).take(24))

    private companion object {
        const val RUNTIME_COMPATIBILITY_VERSION = ":node24-vite8-rolldown-wasm1"
        val EXCLUDED_SOURCE_ENTRIES = setOf(".git", ".npm", "node_modules", "dist")
        val LIVE_SYNC_EXCLUDED_ROOT_FILES =
            setOf("package.json", "package-lock.json", "npm-shrinkwrap.json")
        val PRESERVED_RUNTIME_ENTRIES = setOf("node_modules", ".foldcode-dependencies")
        val FOLDCODE_TEMPLATE_NAMES = setOf("foldcode-vite-app", "foldcode-react-app")
    }
}

internal fun selectWebIntelligenceDirectory(
    sourceDirectory: File,
    runtimeDirectory: File,
    expectedFingerprint: String,
): File {
    val installedFingerprint = File(runtimeDirectory, ".foldcode-dependencies")
        .takeIf(File::isFile)
        ?.readText()
        ?.trim()
    return if (
        File(runtimeDirectory, "node_modules").isDirectory &&
        installedFingerprint == expectedFingerprint
    ) {
        runtimeDirectory
    } else {
        sourceDirectory
    }
}
