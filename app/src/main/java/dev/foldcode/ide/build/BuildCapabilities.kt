package dev.foldcode.ide

import java.io.File

internal object BuildCapability {
    const val Clang = "command:clang"
    const val CMake = "command:cmake"
    const val Ninja = "command:ninja"
    const val Git = "command:git"
    const val GnuArm = "toolchain:gnu-arm-none-eabi"
    const val Python3 = "command:python3"
    const val PicoSdk = "sdk:pico:2.3.0"
}

internal enum class PicoDependencyNetworkPolicy(val markerValue: String, val displayName: String) {
    Ask("ask", "Ask before downloading"),
    Offline("offline", "Offline only"),
    Online("online", "Allow during configuration"),
    ;

    companion object {
        fun fromMarker(value: String?): PicoDependencyNetworkPolicy =
            entries.firstOrNull { it.markerValue == value } ?: Ask
    }
}

internal enum class BuildRequirementSeverity { Warning, Error }

internal data class BuildRequirement(
    val severity: BuildRequirementSeverity,
    val summary: String,
    val detail: String,
    val capability: String? = null,
    val suggestedExtension: String? = null,
)

internal data class PicoProjectRequirementReport(
    val usesRemoteDependencies: Boolean,
    val requirements: List<BuildRequirement>,
) {
    val errors get() = requirements.filter { it.severity == BuildRequirementSeverity.Error }

    fun render(): String = buildString {
        appendLine("Project compatibility check")
        requirements.forEach { requirement ->
            append(if (requirement.severity == BuildRequirementSeverity.Error) "ERROR" else "WARNING")
            append(": ").appendLine(requirement.summary)
            append("  ").appendLine(requirement.detail)
            requirement.suggestedExtension?.let { append("  Action: install ").appendLine(it) }
        }
    }.trimEnd()
}

/** Conservative scanner: only reports requirements that CMake explicitly requests. */
internal object PicoProjectRequirementScanner {
    fun scan(
        project: File,
        configuration: PicoProjectConfiguration,
        installedCapabilities: Set<String>,
    ): PicoProjectRequirementReport {
        val cmake = project.walkTopDown()
            .onEnter { directory -> directory == project || directory.name !in setOf("build", ".git", ".cxx") }
            .filter {
                it.isFile && it.name != "pico_sdk_import.cmake" &&
                    (it.name == "CMakeLists.txt" || it.extension.equals("cmake", true))
            }
            .take(256)
            .mapNotNull { file -> runCatching { file.takeIf { it.length() <= 2L * 1024 * 1024 }?.readText() }.getOrNull() }
            .joinToString("\n")
        val requirements = mutableListOf<BuildRequirement>()
        val needsPython = Regex("find_package\\s*\\(\\s*Python(?:3)?\\b[^)]*\\bREQUIRED\\b", RegexOption.IGNORE_CASE).containsMatchIn(cmake) ||
            Regex("\\bCOMMAND\\s+python3?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(cmake) ||
            Regex("\\bCOMMAND\\s+\\$\\{Python(?:3)?_EXECUTABLE\\}(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(cmake)
        if (needsPython && BuildCapability.Python3 !in installedCapabilities) {
            requirements += BuildRequirement(
                BuildRequirementSeverity.Error,
                "This project requires Python 3 during CMake configuration.",
                "Python is not installed. FoldCode will provide it through a separate runtime extension.",
                BuildCapability.Python3,
                "Python extension",
            )
        }
        val usesGit = Regex("\\bGIT_REPOSITORY\\b", RegexOption.IGNORE_CASE).containsMatchIn(cmake)
        val usesUrl = Regex("\\bURL\\s+['\"]?https?://", RegexOption.IGNORE_CASE).containsMatchIn(cmake)
        val remote = usesGit || usesUrl
        if (remote && configuration.networkPolicy == PicoDependencyNetworkPolicy.Ask) {
            requirements += BuildRequirement(
                BuildRequirementSeverity.Error,
                "This project downloads dependencies during CMake configuration.",
                "Open Configure Build and choose whether this project may use the network.",
            )
        }
        if (remote && configuration.networkPolicy == PicoDependencyNetworkPolicy.Offline) {
            requirements += BuildRequirement(
                BuildRequirementSeverity.Warning,
                "Remote dependencies are disabled for this project.",
                "The build works offline only if those dependencies are already present locally.",
            )
        }
        if (usesGit && configuration.networkPolicy == PicoDependencyNetworkPolicy.Online && "transport:git" !in installedCapabilities) {
            requirements += BuildRequirement(
                BuildRequirementSeverity.Error,
                "CMake needs Git to fetch this project's dependencies.",
                "Network access is enabled, but FoldCode's built-in Git runtime is unavailable. Restart FoldCode.",
                "transport:git",
            )
        }
        val invokesGit = Regex("\\bCOMMAND\\s+git\\b", RegexOption.IGNORE_CASE).containsMatchIn(cmake)
        if (invokesGit && BuildCapability.Git !in installedCapabilities) {
            requirements += BuildRequirement(
                BuildRequirementSeverity.Error,
                "This project directly invokes the Git command-line tool.",
                "FoldCode's built-in Git command is unavailable. Restart FoldCode to restore it.",
                BuildCapability.Git,
            )
        }
        Regex("(?m)(?:(?<![A-Za-z])[A-Za-z]:[\\\\/]|/(?:Users|home)/)[^\"'\\s)]+")
            .findAll(cmake).map { it.value }.distinct().take(3).forEach { path ->
                requirements += BuildRequirement(
                    BuildRequirementSeverity.Warning,
                    "Desktop-only absolute path detected.",
                    "$path does not exist on Android. Replace it with a project-relative path or a CMake option.",
                )
            }
        val requiresGnuArm = Regex(
            "(?:-fplugin=|CMAKE_(?:C|CXX)_COMPILER_ID[^\\n]*GNU|CMAKE_(?:C|CXX)_COMPILER[^\\n]*arm-none-eabi-g(?:cc|\\+\\+))",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(cmake)
        if (requiresGnuArm && BuildCapability.GnuArm !in installedCapabilities) {
            requirements += BuildRequirement(
                BuildRequirementSeverity.Error,
                "This project explicitly requires GNU compiler behavior.",
                "The current Pico target uses FoldCode's Clang-compatible toolchain.",
                BuildCapability.GnuArm,
                "GNU Arm Embedded extension",
            )
        }
        val requestedSdk = Regex("set\\s*\\(\\s*sdkVersion\\s+([0-9.]+)", RegexOption.IGNORE_CASE)
            .find(cmake)?.groupValues?.getOrNull(1)
        if (requestedSdk != null && requestedSdk != PicoSdkRelease.sdkVersion) {
            requirements += BuildRequirement(
                BuildRequirementSeverity.Warning,
                "Project metadata requests Pico SDK $requestedSdk.",
                "FoldCode currently resolves it with installed SDK ${PicoSdkRelease.sdkVersion}; review migration-sensitive code.",
                "sdk:pico:$requestedSdk",
            )
        }
        return PicoProjectRequirementReport(remote, requirements.distinct())
    }
}

internal fun installedBuildCapabilities(
    cpp: FoldCodeExtensionInfo?,
    pico: FoldCodeExtensionInfo?,
    python: FoldCodeExtensionInfo?,
    git: FoldCodeExtensionInfo?,
    gnuArm: FoldCodeExtensionInfo?,
): Set<String> = buildSet {
    cpp?.let { addAll(it.provides) }
    pico?.let { addAll(it.provides) }
    python?.let { addAll(it.provides) }
    git?.let { addAll(it.provides) } // Legacy installs remain harmless during migration.
    gnuArm?.let { addAll(it.provides) }
    addAll(setOf(BuildCapability.Git, "command:git", "transport:git", "scm:git"))
}

internal object BuildFailureAdvisor {
    fun appendAdvice(output: String): String {
        val advice = when {
            Regex("(?:Could NOT find|not found).*Python|Python.*(?:NOTFOUND|not found)", RegexOption.IGNORE_CASE).containsMatchIn(output) ->
                "Action: install the Python extension, then build again."
            Regex("(?:git: not found|Could not find Git|GIT_EXECUTABLE.*NOTFOUND)", RegexOption.IGNORE_CASE).containsMatchIn(output) ->
                "Action: restart FoldCode to restore the built-in native Git runtime."
            Regex("(?:cc1|arm-none-eabi-g(?:cc|\\+\\+)|-fplugin).*(?:not found|cannot|unsupported)", RegexOption.IGNORE_CASE).containsMatchIn(output) ->
                "Action: this project needs the GNU Arm Embedded extension; Clang compatibility mode cannot provide GCC plugins or GCC-only behavior."
            Regex("Permission denied|Network is unreachable|Could not resolve host", RegexOption.IGNORE_CASE).containsMatchIn(output) ->
                "Action: check Configure Build → dependency network access and the device connection."
            Regex("file format not recognized|incompatible.*(?:library|architecture)", RegexOption.IGNORE_CASE).containsMatchIn(output) ->
                "Action: replace desktop precompiled libraries with sources or libraries built for the selected Pico target."
            else -> null
        }
        return if (advice == null || advice in output) output else "$output\n\n$advice"
    }
}
