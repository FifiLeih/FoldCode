package dev.foldcode.ide

import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Executes official Pico CMake projects entirely on the Android device. */
internal class PicoFullBuildRuntimeImpl(private val context: Context) : PicoFullBuildRuntime {
    private val cancelled = AtomicBoolean(false)
    private val activeProcess = AtomicReference<Process?>(null)
    init {
        System.loadLibrary("foldpico_runtime")
    }

    override val version: String = "7"

    override fun probe(): String = nativeProbe()

    override fun build(request: PicoFullBuildRequest, onProgress: (String) -> Unit): PicoFullBuildResult {
        cancelled.set(false)
        return runCatching {
            val project = File(request.projectDirectory).canonicalFile
            val useGnuArm = projectRequiresGnuArm(project)
            val tools = installToolLinks(useGnuArm)
            val sdk = File(request.sdkDirectory).canonicalFile
            require(File(project, "CMakeLists.txt").isFile) { "Project has no CMakeLists.txt" }
            require(File(sdk, "pico_sdk_init.cmake").isFile) { "Full Pico SDK is not installed" }
            val sysroot = prepareCompilerSysroot(request)
            val build = File(project, "build")
            val effectiveBuildType = if (request.debugBuild) {
                PicoBuildType.Debug.cmakeValue
            } else {
                PicoBuildType.fromMarker(request.buildType).cmakeValue
            }
            if (build.isDirectory) ensureBuildIsNotMediaIndexed(build)
            if (request.cleanOnly) {
                if (!File(build, "build.ninja").isFile) {
                    return@runCatching PicoFullBuildResult(true, "CMake project is not configured; nothing to clean")
                }
                val buildLog = runtimeLog(project, "clean")
                // Ninja tool mode removes registered outputs without evaluating
                // the manifest's CMake regeneration rule. The explicit configure
                // below is therefore the only configure/generate pass.
                val cleanCommand = listOf(
                    tools.ninja.absolutePath,
                    "-C", build.absolutePath,
                    "-t", "clean",
                )
                val output = StringBuilder("$ ninja -C build -t clean\n")
                onProgress("Cleaning compiled CMake outputs…")
                output.append(run(cleanCommand, project, tools, 300, buildLog, onProgress))
                migrateFoldCodeMetadata(build)
                val removedSideEffects = removeUntrackedPicoBuildOutputs(build)
                onProgress("Reconfiguring CMake after clean…")
                val reconfigured = build(
                    request.copy(cleanOnly = false, configureOnly = true),
                    onProgress,
                )
                if (!reconfigured.succeeded) {
                    return@runCatching reconfigured.copy(
                        output = output.appendLine("CMake clean succeeded, but reconfiguration failed")
                            .append(reconfigured.output)
                            .toString(),
                    )
                }
                return@runCatching PicoFullBuildResult(
                    succeeded = true,
                    output = boundedTail(output.append(reconfigured.output).appendLine().apply {
                        appendLine("Removed $removedSideEffects untracked Pico build output(s)")
                        appendLine("CMake has been cleaned and reconfigured.")
                        appendLine("Full log: ${buildLog.absolutePath}")
                    }.toString()).trimEnd(),
                )
            }
            val configurationId = listOf(
                "runtime-$version",
                PicoSdkRelease.installId,
                request.boardId,
                request.platform,
                effectiveBuildType,
                compilerProfile(request, useGnuArm),
                sdk.absolutePath,
                sysroot.absolutePath,
            ).joinToString("\n")
            val metadataDirectory = File(build, "CMakeFiles/FoldCode")
            val configurationMarker = File(metadataDirectory, "configuration")
            val cmakeInputsMarker = File(metadataDirectory, "cmake-inputs")
            val legacyConfigurationMarker = File(build, ".foldcode-configuration")
            val storedConfiguration = configurationMarker.takeIf(File::isFile)
                ?: legacyConfigurationMarker.takeIf(File::isFile)
            val cleanRequired = !File(build, "build.ninja").isFile ||
                storedConfiguration?.readText() != configurationId
            val cmakeInputs = cmakeInputFingerprint(project, request)
            val configureRequired = cleanRequired || request.configureOnly ||
                cmakeInputsMarker.takeIf(File::isFile)?.readText() != cmakeInputs
            if (cleanRequired && build.exists()) build.deleteRecursively()
            build.mkdirs()
            ensureBuildIsNotMediaIndexed(build)
            metadataDirectory.mkdirs()
            val buildLog = runtimeLog(project, if (request.configureOnly) "configure" else "build")
            // Ask CMake for the authoritative target graph. The reply is also
            // useful to the editor after projects define targets in subdirectories.
            if (configureRequired) {
                File(build, ".cmake/api/v1/query/codemodel-v2").apply {
                    parentFile?.mkdirs()
                    writeText("")
                }
            }

            val cxxVersion = if (request.platform.contains("riscv", ignoreCase = true)) {
                PicoSdkRelease.riscVGccVersion
            } else PicoSdkRelease.gccVersion
            val cxxIncludes = listOf(
                File(sysroot, "include/c++/$cxxVersion").absolutePath,
                File(sysroot, "include/c++/$cxxVersion/${targetTriple(request)}").absolutePath,
                File(sysroot, "include").absolutePath,
            ).joinToString(";")
            val configure = buildList {
                addAll(listOf(
                tools.cmake.absolutePath,
                "-S", project.absolutePath,
                "-B", build.absolutePath,
                "-G", "Ninja",
                "-DCMAKE_BUILD_TYPE=$effectiveBuildType",
                "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                // FoldCode performs content-based CMake input tracking itself.
                // Suppress Ninja's timestamp-based RERUN_CMAKE edge because
                // Android shared storage timestamps can make it regenerate on
                // every Compile Project invocation.
                "-DCMAKE_SUPPRESS_REGENERATION=ON",
                "-DCMAKE_MAKE_PROGRAM=${tools.ninja.absolutePath}",
                "-DCMAKE_PREFIX_PATH=${tools.prefix.absolutePath}",
                "-DCMAKE_MODULE_PATH=${File(sdk, "external").absolutePath}",
                "-DPICO_SDK_PATH=${sdk.absolutePath}",
                "-DPICO_BOARD=${request.boardId}",
                "-DPICO_PLATFORM=${request.platform}",
                "-DPICO_COMPILER=${compilerProfile(request, useGnuArm)}",
                "-DPICO_TOOLCHAIN_PATH=${tools.bin.absolutePath}",
                "-DFETCHCONTENT_FULLY_DISCONNECTED=${if (request.dependencyNetworkPolicy == PicoDependencyNetworkPolicy.Online.markerValue) "OFF" else "ON"}",
                "-DFOLDCODE_PICO_BOOT_PAD=${tools.bootPad.absolutePath}",
                ))
                if (!useGnuArm) addAll(listOf(
                    "-DPICO_COMPILER_SYSROOT=${sysroot.absolutePath}",
                    // CMake's Android host root-path rules can hide an explicitly
                    // supplied embedded sysroot from find_path(). Give the Pico
                    // Clang profile the already validated header root directly.
                    "-D_CLANG_HEADERS_DIR=${sysroot.absolutePath}",
                    "-DCMAKE_TRY_COMPILE_PLATFORM_VARIABLES=PICO_COMPILER_SYSROOT;_CLANG_HEADERS_DIR",
                    "-DCMAKE_CXX_STANDARD_INCLUDE_DIRECTORIES=$cxxIncludes",
                    "-DCMAKE_C_STANDARD_INCLUDE_DIRECTORIES=${File(sysroot, "include").absolutePath}",
                    "-DCMAKE_EXE_LINKER_FLAGS=-fuse-ld=${tools.linker.absolutePath} " +
                        "--sysroot=${sysroot.absolutePath} -nostartfiles -nostdlib++ " +
                        "-L${File(sysroot, "lib").absolutePath}",
                    "-DCMAKE_PROJECT_INCLUDE=${tools.linkOptions.absolutePath}",
                ))
                request.dependencySourceDirectories.toSortedMap().forEach { (name, directory) ->
                    val cmakeName = name.uppercase().replace(Regex("[^A-Z0-9_]"), "_")
                    add("-DFETCHCONTENT_SOURCE_DIR_$cmakeName=$directory")
                }
            }
            val output = StringBuilder()
            if (configureRequired) {
                output.appendLine("$ cmake -S . -B build -G Ninja")
                onProgress(if (cleanRequired) "Configuring Full CMake project…" else "Refreshing changed CMake configuration…")
                output.append(run(configure, project, tools, 300, buildLog, onProgress))
                configurationMarker.writeText(configurationId)
                cmakeInputsMarker.writeText(cmakeInputs)
                legacyConfigurationMarker.delete()
                persistCMakeExecutableTargets(project, build)
            } else {
                output.appendLine("Using existing CMake/Ninja configuration")
                onProgress("CMake configuration is current; starting build…")
            }
            // Android shared storage can retain an object's previous timestamp
            // after Clang atomically replaces it. Persist the timestamp inside
            // each compiler command before Ninja records dependency metadata.
            installNinjaSharedStorageRules(build)
            // Always refresh explicitly: Android shared storage can otherwise make
            // Ninja's automatic regeneration loop on coarse/inverted timestamps.
            normalizeNinjaTimestamp(project, build)
            if (request.configureOnly) {
                return@runCatching PicoFullBuildResult(
                    succeeded = true,
                    output = boundedTail(output.appendLine("CMake configuration succeeded").apply {
                        appendLine("Compile database: ${File(build, "compile_commands.json").absolutePath}")
                        appendLine("Full log: ${buildLog.absolutePath}")
                    }.toString()).trimEnd(),
                )
            }
            val thermalStatus = currentThermalStatus()
            val thermalLimited = request.thermalAware && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            val requestedJobs = request.parallelJobs.takeIf { it > 0 }
                ?: (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
            val parallelJobs = if (thermalLimited) minOf(requestedJobs, 2) else requestedJobs.coerceIn(1, 8)
            val buildCommand = buildList {
                addAll(listOf(tools.cmake.absolutePath, "--build", build.absolutePath, "--parallel", parallelJobs.toString()))
                request.target?.takeIf(String::isNotBlank)?.let { addAll(listOf("--target", it)) }
            }
            output.append("$ cmake --build build --parallel $parallelJobs")
            request.target?.takeIf(String::isNotBlank)?.let { output.append(" --target $it") }
            output.appendLine()
            output.appendLine(
                "Ninja workers: $parallelJobs (${if (thermalLimited) "thermal limited" else "normal"})",
            )
            val artifactsBeforeBuild = discoverProjectArtifacts(build).associate { artifact ->
                artifact.absolutePath to ArtifactStamp(artifact.length(), artifact.lastModified())
            }
            onProgress("Starting Ninja build…")
            output.append(run(buildCommand, project, tools, 1_800, buildLog, onProgress))
            val discoveredArtifacts = discoverProjectArtifacts(build)
            val changedArtifacts = discoveredArtifacts.filter { artifact ->
                artifactsBeforeBuild[artifact.absolutePath] != ArtifactStamp(artifact.length(), artifact.lastModified())
            }
            // A selected CMake target may generate or depend on several named
            // firmware images. Prefer every artifact touched by this invocation;
            // for a no-op incremental build, fall back to the selected target and
            // finally to all project-owned artifacts.
            val artifacts = changedArtifacts.ifEmpty {
                request.target?.takeIf(String::isNotBlank)?.let { selectedTarget ->
                    discoveredArtifacts.filter { it.nameWithoutExtension == selectedTarget }.ifEmpty { discoveredArtifacts }
                } ?: discoveredArtifacts
            }
            val artifactPaths = artifacts
                .map(File::getAbsolutePath)
                .sorted()
            PicoFullBuildResult(
                succeeded = true,
                output = boundedTail(output.appendLine("Build succeeded").apply {
                    artifactPaths
                        .filter { it.endsWith(".uf2", ignoreCase = true) }
                        .forEach { appendLine("UF2: $it") }
                    appendLine("Full log: ${buildLog.absolutePath}")
                }.toString()).trimEnd(),
                artifacts = artifactPaths,
            )
        }.getOrElse { error ->
            if (cancelled.get() || error is BuildCancelledException) {
                PicoFullBuildResult(false, "Build cancelled")
            } else {
                PicoFullBuildResult(false, "Full CMake build failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeProcess.get()?.terminateTree()
    }

    private data class ArtifactStamp(val length: Long, val modifiedAt: Long)

    private fun discoverProjectArtifacts(build: File): List<File> = build.walkTopDown()
        .onEnter { directory ->
            if (directory == build) return@onEnter true
            val relative = directory.relativeTo(build).invariantSeparatorsPath
            relative != "CMakeFiles" &&
                !relative.startsWith("CMakeFiles/") &&
                relative != "pico-sdk" &&
                !relative.startsWith("pico-sdk/") &&
                relative != "_deps" &&
                !relative.startsWith("_deps/")
        }
        .filter { artifact ->
            artifact.isFile && artifact.extension.lowercase() in setOf("elf", "uf2", "bin", "hex")
        }
        .toList()

    private fun ensureBuildIsNotMediaIndexed(build: File) {
        File(build, ".nomedia").takeUnless(File::exists)?.writeText("")
    }

    private fun currentThermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

    private fun prepareCompilerSysroot(request: PicoFullBuildRequest): File {
        val root = File(context.filesDir, "pico-cmake-sysroot-${request.platform}")
        val marker = File(root, ".version")
        // The compiler headers/runtime are architecture-specific, not board-specific.
        // Pico/Pico W and Pico 2/Pico 2 W must therefore reuse one stable sysroot.
        // Recreating it for a sibling board changes every header timestamp and makes
        // Ninja rebuild an otherwise unchanged project from scratch.
        val version = "${PicoSdkRelease.installId}:shared-headers-v4:${request.platform}"
        val include = File(root, "include")
        if (marker.takeIf(File::isFile)?.readText() == version && hasRequiredCompilerHeaders(include)) return root
        // Older releases linked this directory directly into the installed
        // toolchain. File.deleteRecursively() can traverse directory symlinks,
        // so detach it before replacing the generated sysroot. Otherwise
        // switching between boards on the same platform (for example pico2 and
        // pico2_w) can erase the shared compiler headers.
        if (Files.isSymbolicLink(include.toPath())) Files.deleteIfExists(include.toPath())
        root.deleteRecursively()
        root.mkdirs()
        val sharedHeaders = File(request.compilerHeadersDirectory).canonicalFile
        require(hasRequiredCompilerHeaders(sharedHeaders)) { "Compiler headers are unavailable or incomplete" }
        // Keep this as an independent generated cache. A symlink saves space,
        // but makes ordinary cache deletion capable of damaging the extension.
        require(sharedHeaders.copyRecursively(include, overwrite = true)) {
            "Could not prepare compiler headers"
        }
        require(hasRequiredCompilerHeaders(include)) { "Generated compiler sysroot is incomplete" }
        File(request.compilerRuntimeDirectory).copyRecursively(File(root, "lib"), overwrite = true)
        marker.writeText(version)
        return root
    }

    private fun hasRequiredCompilerHeaders(headers: File): Boolean =
        headers.isDirectory &&
            File(headers, "assert.h").isFile &&
            File(headers, "stdint.h").isFile

    /**
     * Android's emulated shared storage can publish CMakeCache.txt with a newer
     * timestamp than build.ninja even though both were written by the same CMake
     * configure. Ninja then regenerates forever instead of compiling. Make the
     * generator output unambiguously newest before invoking Ninja.
     */
    private fun normalizeNinjaTimestamp(project: File, build: File) {
        val ninja = File(build, "build.ninja")
        if (!ninja.isFile) return
        val newestInput = listOf(File(build, "CMakeCache.txt"), File(project, "CMakeLists.txt"))
            .filter(File::isFile)
            .maxOfOrNull(File::lastModified)
            ?: System.currentTimeMillis()
        require(ninja.setLastModified(maxOf(System.currentTimeMillis(), newestInput) + 10_000L)) {
            "Could not finalize build.ninja timestamp"
        }
    }

    /**
     * Pico SDK post-processing tools create a few side-effect files that are not
     * declared as Ninja outputs (notably OTP JSON and linker maps). Ninja cannot
     * clean what is absent from its graph, so remove only known compiled/report
     * artifacts while retaining the configured CMake/Ninja tree.
     */
    private fun removeUntrackedPicoBuildOutputs(build: File): Int {
        val compiledExtensions = setOf("o", "obj", "a", "elf", "uf2", "bin", "hex", "dis", "map")
        var removed = 0
        build.walkBottomUp().filter(File::isFile).forEach { file ->
            val name = file.name.lowercase()
            val generatedOutput = file.extension.lowercase() in compiledExtensions ||
                name == "otp.json" ||
                name.endsWith(".otp.json") ||
                name in setOf(
                    "foldcode-build.log",
                    "foldcode-clean.log",
                    ".ninja_deps",
                    ".ninja_log",
                    ".ninja_lock",
                    ".foldcode-configuration",
                    ".foldcode-targets",
                )
            if (generatedOutput && file.delete()) removed++
        }
        File(build, ".cmake").takeIf(File::exists)?.let { directory ->
            if (directory.deleteRecursively()) removed++
        }
        return removed
    }

    private fun migrateFoldCodeMetadata(build: File) {
        val directory = File(build, "CMakeFiles/FoldCode").apply { mkdirs() }
        listOf(
            File(build, ".foldcode-configuration") to File(directory, "configuration"),
            File(build, ".foldcode-targets") to File(directory, "targets"),
        ).forEach { (legacy, current) ->
            if (legacy.isFile && !current.isFile) legacy.copyTo(current, overwrite = false)
        }
    }

    /** Uses CMake's codemodel instead of trying to parse arbitrary CMake syntax. */
    private fun persistCMakeExecutableTargets(project: File, build: File) {
        runCatching {
            val reply = File(build, ".cmake/api/v1/reply")
            val index = reply.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("index-") && it.extension == "json" }
                .maxByOrNull(File::lastModified) ?: return@runCatching
            val indexJson = JSONObject(index.readText())
            val objects = indexJson.optJSONArray("objects") ?: return@runCatching
            var codemodelFile: String? = null
            for (position in 0 until objects.length()) {
                val item = objects.getJSONObject(position)
                if (item.optString("kind") == "codemodel") codemodelFile = item.optString("jsonFile")
            }
            val codemodelName = codemodelFile?.takeIf(String::isNotBlank) ?: return@runCatching
            val codemodel = JSONObject(File(reply, codemodelName).readText())
            val configurations = codemodel.optJSONArray("configurations") ?: return@runCatching
            if (configurations.length() == 0) return@runCatching
            val references = configurations.getJSONObject(0).optJSONArray("targets") ?: return@runCatching
            val projectRoot = project.canonicalFile.toPath()
            val targets = buildList {
                for (position in 0 until references.length()) {
                    val reference = references.getJSONObject(position)
                    val targetFile = reference.optString("jsonFile").takeIf(String::isNotBlank) ?: continue
                    val target = JSONObject(File(reply, targetFile).readText())
                    if (target.optString("type") != "EXECUTABLE") continue
                    val source = target.optJSONObject("paths")?.optString("source").orEmpty()
                    val sourceDirectory = if (File(source).isAbsolute) File(source) else File(project, source)
                    if (!runCatching { sourceDirectory.canonicalFile.toPath().startsWith(projectRoot) }.getOrDefault(false)) continue
                    reference.optString("name").takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct().sorted()
            File(build, "CMakeFiles/FoldCode/targets").apply {
                parentFile?.mkdirs()
                writeText(targets.joinToString("\n"))
            }
            File(build, ".foldcode-targets").delete()
        }
    }

    private fun runtimeLog(project: File, operation: String): File = File(
        context.cacheDir,
        "pico-${project.absolutePath.hashCode().toUInt().toString(16)}-$operation.log",
    ).apply { writeText("") }

    /**
     * Content-based freshness check avoids unreliable shared-storage timestamps.
     * Ordinary source edits go straight to Ninja; only inputs that can change the
     * generated build graph cause Compile Project to run CMake again.
     */
    private fun cmakeInputFingerprint(project: File, request: PicoFullBuildRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.toByteArray())
            digest.update(0)
        }
        add("policy=${request.dependencyNetworkPolicy}")
        val roots = buildList {
            add("project" to project.canonicalFile)
            request.dependencySourceDirectories.toSortedMap().forEach { (name, path) ->
                add("dependency:$name" to File(path).canonicalFile)
            }
        }
        roots.forEach { (label, root) ->
            add("$label=${root.absolutePath}")
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .onEnter { directory ->
                    directory == root || directory.name !in setOf("build", ".git", ".gradle", ".cxx", ".foldcode")
                }
                .filter { file ->
                    file.isFile && file.length() <= 4L * 1024 * 1024 &&
                        (file.name == "CMakeLists.txt" || file.extension.lowercase() in setOf("cmake", "in"))
                }
                .take(2_048)
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .forEach { file ->
                    add("$label/${file.relativeTo(root).invariantSeparatorsPath}")
                    digest.update(file.readBytes())
                    digest.update(0)
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installToolLinks(useGnuArm: Boolean): RuntimeTools {
        BundledToolchain(context).install()
        val prefix = File(context.filesDir, "usr")
        val bin = File(prefix, "bin").apply { mkdirs() }
        File(prefix, "include/android").apply { mkdirs() }
        File(prefix, "include/android/api-level.h").writeText("#define __ANDROID_API__ 28\n")
        val native = File(context.applicationInfo.nativeLibraryDir)
        fun link(name: String, library: String): File {
            val destination = File(bin, name)
            Files.deleteIfExists(destination.toPath())
            Files.createSymbolicLink(destination.toPath(), File(native, library).toPath())
            return destination
        }
        val cmake = link("cmake", "libfoldcmake.so")
        val ninja = link("ninja", "libfoldninja.so")
        val picotoolRuntime = File(context.filesDir, "pico-tools-runtime")
        require(File(picotoolRuntime, "bin/picotool").isFile) {
            "The Pico extension's picotool component is not installed"
        }
        require(File(picotoolRuntime, "lib/libusb1.0.so").isFile) {
            "The Pico extension's USB runtime is not installed"
        }
        // The APK contains only the small Android/PRoot host. The actual
        // picotool executable and libusb are delivered by the Pico extension.
        val picotool = link("picotool", "foldpicotool.so")
        val pioasm = link("pioasm", "foldpioasm.so")
        val extensionManager = FoldCodeExtensionManager(context)
        if (extensionManager.isPythonInstalled()) {
            extensionManager.installPythonRuntime()
            val python = link("python3", "foldpython.so")
            Files.deleteIfExists(File(bin, "python").toPath())
            Files.createSymbolicLink(File(bin, "python").toPath(), python.toPath())
        }
        // Git is a base-app service shared with Source Control and the terminal.
        // Keep the conventional command available to arbitrary project CMake.
        link("git", "foldgit.so")
        val gnuArmLauncher = if (useGnuArm) {
            extensionManager.installGnuArmRuntime()
            File(native, "foldgnuarm.so")
        } else null
        val linker = link("ld.lld", "foldlld.so")
        val bootPad = link("foldpicopad", "foldpicopad.so")
        val linkOptions = File(prefix, "share/foldcode/pico-link-options.cmake").apply {
            parentFile?.mkdirs()
            writeText(
                // Runtime selection is meaningful for C/C++ final links, but
                // not for the SDK's assembly-only boot2 link. Generator
                // expressions keep the options attached to their real users.
                // CMake recognizes .s/.S itself. Register the common .asm
                // spelling before targets are declared so imported projects do
                // not need a source-property workaround for GNU-style assembly.
                "list(APPEND CMAKE_ASM_SOURCE_FILE_EXTENSIONS asm)\n" +
                    "list(REMOVE_DUPLICATES CMAKE_ASM_SOURCE_FILE_EXTENSIONS)\n" +
                    "file(GLOB_RECURSE FOLDCODE_ASM_SOURCES \"\${CMAKE_SOURCE_DIR}/*.asm\")\n" +
                    "if(FOLDCODE_ASM_SOURCES)\n" +
                    "  set_source_files_properties(\${FOLDCODE_ASM_SOURCES} PROPERTIES LANGUAGE ASM COMPILE_OPTIONS \"-x;assembler\")\n" +
                    "endif()\n" +
                    "if(NOT FOLDCODE_PICO_LINK_OPTIONS_APPLIED)\n" +
                    "  add_link_options(\n" +
                    "    \"\$<\$<OR:\$<LINK_LANGUAGE:C>,\$<LINK_LANGUAGE:CXX>>:--rtlib=libgcc>\"\n" +
                    "    \"\$<\$<OR:\$<LINK_LANGUAGE:C>,\$<LINK_LANGUAGE:CXX>>:--unwindlib=none>\"\n" +
                    "  )\n" +
                    "  set(FOLDCODE_PICO_LINK_OPTIONS_APPLIED TRUE)\n" +
                    "endif()\n",
            )
        }
        val nativeClang = File(native, "libfoldclang.so")
        compilerLink(File(bin, "clang"), nativeClang)
        compilerLink(File(bin, "clang++"), nativeClang)
        link("llvm-objcopy", "libfoldobjcopy.so")
        link("llvm-objdump", "libfoldobjdump.so")
        link("llvm-nm", "foldnm.so")
        link("llvm-ar", "libfoldar.so")
        link("llvm-ranlib", "libfoldranlib.so")
        // Expose the canonical LLVM RISC-V triple. The SDK probes several GNU
        // prefixes; advertising riscv32-pico-elf first would make Clang receive
        // a vendor-specific triple that it does not consistently normalize.
        listOf("arm-none-eabi", "riscv32-unknown-elf").forEach { triple ->
            val riscv = triple.startsWith("riscv")
            if (!riscv && gnuArmLauncher != null) {
                listOf("gcc", "g++", "c++", "cpp", "as", "ld", "ld.bfd", "ar", "ranlib", "nm", "objcopy", "objdump", "readelf", "size", "strings", "strip")
                    .forEach { compilerLink(File(bin, "$triple-$it"), gnuArmLauncher) }
            } else {
                compilerLink(File(bin, "$triple-gcc"), nativeClang)
                compilerLink(File(bin, "$triple-g++"), nativeClang)
                link("$triple-objcopy", if (riscv) "libfoldriscvobjcopy.so" else "libfoldobjcopy.so")
                link("$triple-objdump", if (riscv) "libfoldriscvobjdump.so" else "libfoldobjdump.so")
                link("$triple-nm", "foldnm.so")
                link("$triple-ar", if (riscv) "libfoldriscvar.so" else "libfoldar.so")
                link("$triple-ranlib", if (riscv) "libfoldriscvranlib.so" else "libfoldranlib.so")
            }
        }
        writeToolPackage(prefix, "picotool", "2.3.0", picotool)
        writeToolPackage(prefix, "pioasm", PicoSdkRelease.sdkVersion, pioasm)
        return RuntimeTools(prefix, bin, cmake, ninja, linker, bootPad, linkOptions, useGnuArm)
    }

    private fun compilerLink(file: File, clang: File): File {
        Files.deleteIfExists(file.toPath())
        Files.createSymbolicLink(file.toPath(), clang.toPath())
        return file
    }

    private fun writeToolPackage(prefix: File, name: String, version: String, executable: File) {
        val directory = File(prefix, "lib/cmake/$name").apply { mkdirs() }
        File(directory, "$name-config.cmake").writeText(
            "add_executable($name IMPORTED GLOBAL)\n" +
                "set_property(TARGET $name PROPERTY IMPORTED_LOCATION \"${executable.absolutePath}\")\n" +
                "set(${name}_FOUND TRUE)\nset(${name}_VERSION \"$version\")\n",
        )
        File(directory, "$name-config-version.cmake").writeText(
            "set(PACKAGE_VERSION \"$version\")\n" +
                "set(PACKAGE_VERSION_COMPATIBLE TRUE)\nset(PACKAGE_VERSION_EXACT TRUE)\n",
        )
    }

    private fun run(
        command: List<String>,
        directory: File,
        tools: RuntimeTools,
        timeoutSeconds: Long,
        logFile: File,
        onProgress: (String) -> Unit,
    ): String {
        if (cancelled.get()) throw BuildCancelledException()
        val pidSession = BuildPidRegistry.open(context.cacheDir)
        val process = try {
            ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["PATH"] = "${tools.bin.absolutePath}:/system/bin"
                    environment()["PREFIX"] = tools.prefix.absolutePath
                    val toolchain = BundledToolchain(context)
                    environment()["LD_LIBRARY_PATH"] =
                        "${toolchain.nativeDependencies.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                    environment()["FOLDCODE_LLD_CORE"] =
                        File(toolchain.nativeDependencies, "libfoldlldcore.so").absolutePath
                    environment()["FOLDCODE_PICOTOOL_ROOT"] =
                        File(context.filesDir, "pico-tools-runtime").absolutePath
                    environment()["FOLDCODE_PROJECT_ROOT"] = directory.canonicalPath
                    environment()["CMAKE_ROOT"] = File(tools.prefix, "share/cmake-4.0").absolutePath
                    environment()["FOLDCODE_APK_PATH"] = context.applicationInfo.sourceDir
                    if (!tools.gnuArm) {
                        pidSession.configureNative(
                            environment(),
                            File(context.applicationInfo.nativeLibraryDir),
                        )
                    } else {
                        // The GNU Arm launcher enters a glibc guest. Its Bionic
                        // host is still attached directly; do not preload an
                        // Android library into the guest compiler.
                        pidSession.configure(environment())
                    }
                    NativeGit(context).configure(environment(), tools.bin)
                    File(context.filesDir, "gnu-arm-runtime").takeIf(File::isDirectory)?.let { gnuRoot ->
                        environment()["FOLDCODE_GNU_ARM_ROOT"] = gnuRoot.absolutePath
                    }
                    File(context.filesDir, "python-runtime").takeIf(File::isDirectory)?.let { pythonRoot ->
                        environment()["FOLDCODE_PYTHON_ROOT"] = pythonRoot.absolutePath
                        environment()["PYTHONHOME"] = File(pythonRoot, "prefix").absolutePath
                    }
                    if (!tools.gnuArm) {
                        val clangResourceFlag = "-resource-dir=${toolchain.resourceDir.absolutePath}"
                        environment()["CFLAGS"] = clangResourceFlag
                        environment()["CXXFLAGS"] = clangResourceFlag
                        environment()["ASMFLAGS"] = clangResourceFlag
                    }
                }
                .start()
                .also(pidSession::attach)
        } catch (error: Exception) {
            pidSession.close()
            throw error
        }
        activeProcess.set(process)
        try {
            val output = StringBuilder()
            FileOutputStream(logFile, true).bufferedWriter().use { log ->
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        log.appendLine(line)
                        output.appendLine(line)
                        if (output.length > MAX_UI_BUILD_LOG_CHARS + 16_384) {
                            output.delete(0, output.length - MAX_UI_BUILD_LOG_CHARS)
                        }
                        if (line.isNotBlank()) onProgress(line)
                    }
                }
            }
            if (cancelled.get()) throw BuildCancelledException()
            require(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Command timed out after $timeoutSeconds seconds"
            }
            if (cancelled.get()) throw BuildCancelledException()
            val rendered = output.toString()
            require(process.exitValue() == 0) { rendered.trim().ifBlank { "Command exited ${process.exitValue()}" } }
            return rendered
        } finally {
            activeProcess.compareAndSet(process, null)
            pidSession.close()
        }
    }

    private fun targetTriple(request: PicoFullBuildRequest) =
        if (request.platform.contains("riscv", true)) "riscv32-unknown-elf" else "arm-none-eabi"

    private fun compilerProfile(request: PicoFullBuildRequest, useGnuArm: Boolean) = when {
        request.platform.contains("riscv", true) -> "pico_riscv_gcc"
        useGnuArm && request.platform.startsWith("rp2040", true) -> "pico_arm_cortex_m0plus_gcc"
        useGnuArm -> "pico_arm_cortex_m33_gcc"
        request.platform.startsWith("rp2040", true) -> "pico_arm_cortex_m0plus_clang"
        else -> "pico_arm_cortex_m33_clang"
    }

    private fun projectRequiresGnuArm(project: File): Boolean = project.walkTopDown()
        .onEnter { directory -> directory == project || directory.name !in setOf("build", ".git", ".cxx") }
        .filter { it.isFile && (it.name == "CMakeLists.txt" || it.extension.equals("cmake", true)) && it.length() <= 2L * 1024 * 1024 }
        .take(256)
        .any { file ->
            Regex(
                "(?:-fplugin=|CMAKE_(?:C|CXX)_COMPILER_ID[^\\n]*GNU|CMAKE_(?:C|CXX)_COMPILER[^\\n]*arm-none-eabi-g(?:cc|\\+\\+))",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(runCatching { file.readText() }.getOrDefault(""))
        }

    private fun boundedTail(value: String): String =
        if (value.length <= MAX_UI_BUILD_LOG_CHARS) value
        else "… earlier output is available in the full log listed below …\n" + value.takeLast(MAX_UI_BUILD_LOG_CHARS)

    private data class RuntimeTools(
        val prefix: File,
        val bin: File,
        val cmake: File,
        val ninja: File,
        val linker: File,
        val bootPad: File,
        val linkOptions: File,
        val gnuArm: Boolean,
    )

    private external fun nativeProbe(): String

    private class BuildCancelledException : RuntimeException("Build cancelled")

    private companion object {
        const val MAX_UI_BUILD_LOG_CHARS = 256 * 1024
    }
}
