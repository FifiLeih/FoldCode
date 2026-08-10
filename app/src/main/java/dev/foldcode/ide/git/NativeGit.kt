package dev.foldcode.ide

import android.content.Context
import android.os.Environment
import android.util.Base64
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** One upstream native Git engine shared by Source Control and the terminal. */
internal class NativeGit(private val context: Context) {
    data class Result(val exitCode: Int, val output: String)

    private val runtimeDirectory = File(context.filesDir, "native-git")
    private val helpersDirectory = File(runtimeDirectory, "bin")
    private val templatesDirectory = File(runtimeDirectory, "templates")
    private val caBundle = File(runtimeDirectory, "cacert.pem")
    private val launcher = File(context.applicationInfo.nativeLibraryDir, "foldgit.so")

    init {
        helpersDirectory.mkdirs()
        templatesDirectory.mkdirs()
        listOf(
            "git",
            "git-receive-pack",
            "git-upload-archive",
            "git-upload-pack",
        ).forEach { installLink(it, "foldgit.so") }
        installLink("git-remote-http", "libfoldgitremotehttp.so")
        installLink("git-remote-https", "libfoldgitremotehttp.so")
        if (!caBundle.isFile) {
            caBundle.parentFile?.mkdirs()
            context.assets.open("git/cacert.pem").use { input ->
                caBundle.outputStream().use(input::copyTo)
            }
        }
    }

    fun configure(environment: MutableMap<String, String>, commandDirectory: File = helpersDirectory) {
        environment["GIT_EXEC_PATH"] = helpersDirectory.absolutePath
        environment["GIT_TEMPLATE_DIR"] = templatesDirectory.absolutePath
        environment["GIT_SSL_CAINFO"] = caBundle.absolutePath
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_TERMINAL_PROMPT"] = "0"
        environment["GIT_CONFIG_COUNT"] = "1"
        environment["GIT_CONFIG_KEY_0"] = "safe.directory"
        environment["GIT_CONFIG_VALUE_0"] = File(
            Environment.getExternalStorageDirectory(),
            "FoldCode/Projects/*",
        ).absolutePath
        environment["PATH"] = "${commandDirectory.absolutePath}:${helpersDirectory.absolutePath}:/system/bin:/system/xbin"
    }

    fun run(
        directory: File,
        arguments: List<String>,
        username: String = "",
        token: String = "",
        timeoutSeconds: Long = 120,
    ): Result {
        require(launcher.isFile) { "Native Git is unavailable" }
        val process = ProcessBuilder(listOf(launcher.absolutePath) + arguments)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                configure(environment())
                environment()["HOME"] = directory.absolutePath
                if (token.isNotBlank()) {
                    val identity = "${username.trim()}:$token".toByteArray(Charsets.UTF_8)
                    val position = environment()["GIT_CONFIG_COUNT"]?.toIntOrNull() ?: 0
                    environment()["GIT_CONFIG_COUNT"] = (position + 1).toString()
                    environment()["GIT_CONFIG_KEY_$position"] = "http.extraHeader"
                    environment()["GIT_CONFIG_VALUE_$position"] =
                        "Authorization: Basic ${Base64.encodeToString(identity, Base64.NO_WRAP)}"
                }
            }
            .start()
        val output = ByteArrayOutputStream()
        val outputReader = Thread({
            runCatching { process.inputStream.use { it.copyTo(output) } }
        }, "FoldCode-native-git-output").apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            outputReader.join(1_000)
            error("Git command timed out")
        }
        outputReader.join(2_000)
        return Result(process.exitValue(), output.toString(Charsets.UTF_8.name()).trim())
    }

    fun requireSuccess(
        directory: File,
        arguments: List<String>,
        username: String = "",
        token: String = "",
    ): String {
        val result = run(directory, arguments, username, token)
        require(result.exitCode == 0) { result.output.ifBlank { "Git command failed (${result.exitCode})" } }
        return result.output
    }

    private fun installLink(name: String, packagedName: String) {
        val target = File(context.applicationInfo.nativeLibraryDir, packagedName)
        val link = File(helpersDirectory, name)
        if (!target.isFile) return
        runCatching {
            if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }
    }
}
