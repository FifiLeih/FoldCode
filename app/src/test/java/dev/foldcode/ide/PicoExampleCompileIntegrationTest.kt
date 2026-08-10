package dev.foldcode.ide

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Optional host smoke test; set the five PICO_TEST_* variables to enable it. */
class PicoExampleCompileIntegrationTest {
    @Test
    fun allCuratedCAndCppExamplesBuildAgainstTheFullSdk() {
        val sdk = environmentFile("PICO_TEST_SDK")
        val toolchain = environmentFile("PICO_TEST_TOOLCHAIN")
        val cmake = environmentFile("PICO_TEST_CMAKE", executable = true)
        val ninja = environmentFile("PICO_TEST_NINJA", executable = true)
        val picotool = environmentFile("PICO_TEST_PICOTOOL", executable = true)
        assumeTrue(sdk != null && toolchain != null && cmake != null && ninja != null && picotool != null)

        val root = Files.createTempDirectory("foldcode-pico-examples-").toFile()
        try {
            listOf(PicoBoard.Pico, PicoBoard.PicoW, PicoBoard.Pico2).forEach { board ->
                buildCatalog(root, board, sdk!!, toolchain!!, cmake!!, ninja!!, picotool!!)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun buildCatalog(
        root: File,
        board: PicoBoard,
        sdk: File,
        toolchain: File,
        cmake: File,
        ninja: File,
        picotool: File,
    ) {
        val sourceDirectory = File(root, board.bundleId).apply { mkdirs() }
        val targets = buildList {
            PicoExample.entries.forEach { example ->
                listOf(PicoProjectLanguage.C, PicoProjectLanguage.Cpp).forEach { language ->
                    val target = "${example.name.lowercase()}_${language.name.lowercase()}"
                    val source = "$target.${if (language == PicoProjectLanguage.C) "c" else "cpp"}"
                    File(sourceDirectory, source).writeText(picoExampleSource(board, example))
                    add(Triple(target, source, example.sdkLibraries))
                }
            }
        }
        File(sourceDirectory, "pico_sdk_import.cmake").writeText(
            "include(\"${sdk.invariantSeparatorsPath}/pico_sdk_init.cmake\")\n",
        )
        File(sourceDirectory, "CMakeLists.txt").writeText(buildString {
            appendLine("cmake_minimum_required(VERSION 3.13)")
            appendLine("set(CMAKE_C_STANDARD 11)")
            appendLine("set(CMAKE_CXX_STANDARD 17)")
            appendLine("set(PICO_BOARD ${board.boardId})")
            appendLine("set(PICO_PLATFORM ${board.platform})")
            appendLine("include(pico_sdk_import.cmake)")
            appendLine("project(foldcode_example_smoke C CXX ASM)")
            appendLine("pico_sdk_init()")
            targets.forEach { (target, source, libraries) ->
                appendLine("add_executable($target $source)")
                val links = buildList {
                    add("pico_stdlib")
                    picoWirelessArchitectureTarget(board)?.let(::add)
                    addAll(libraries)
                }
                appendLine("target_link_libraries($target ${links.joinToString(" ")})")
                appendLine("pico_enable_stdio_usb($target 1)")
                appendLine("pico_enable_stdio_uart($target 1)")
                appendLine("pico_add_extra_outputs($target)")
            }
        })

        val buildDirectory = File(sourceDirectory, "build")
        runProcess(
            sourceDirectory,
            sdk,
            toolchain,
            picotool,
            cmake.absolutePath,
            "-S", sourceDirectory.absolutePath,
            "-B", buildDirectory.absolutePath,
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
        )
        runProcess(
            sourceDirectory,
            sdk,
            toolchain,
            picotool,
            cmake.absolutePath,
            "--build", buildDirectory.absolutePath,
        )
    }

    private fun runProcess(
        directory: File,
        sdk: File,
        toolchain: File,
        picotool: File,
        vararg command: String,
    ) {
        val process = ProcessBuilder(*command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                environment()["PICO_SDK_PATH"] = sdk.absolutePath
                environment()["PICO_TOOLCHAIN_PATH"] = toolchain.absolutePath
                environment()["PATH"] = listOf(
                    requireNotNull(picotool.parentFile).absolutePath,
                    File(toolchain, "bin").absolutePath,
                    environment()["PATH"].orEmpty(),
                ).joinToString(File.pathSeparator)
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) fail("${command.joinToString(" ")} failed:\n$output")
    }

    private fun environmentFile(name: String, executable: Boolean = false): File? =
        System.getenv(name)?.let(::File)?.takeIf { it.exists() && (!executable || it.canExecute()) }
}
