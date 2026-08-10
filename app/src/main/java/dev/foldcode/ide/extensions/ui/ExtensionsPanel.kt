package dev.foldcode.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val ExtensionBackground = Color(0xFF202329)
private val ExtensionForeground = Color(0xFFD8DEE9)
private val ExtensionMuted = Color(0xFF9299A8)
private val ExtensionAccent = Color(0xFF1683D8)

@Composable
internal fun ExtensionsPanel(
    cppInfo: FoldCodeExtensionInfo?,
    picoInfo: FoldCodeExtensionInfo?,
    pythonInfo: FoldCodeExtensionInfo?,
    rustInfo: FoldCodeExtensionInfo?,
    gnuLanguagesInfo: FoldCodeExtensionInfo?,
    webInfo: FoldCodeExtensionInfo?,
    gitInfo: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstallFromServer: () -> Unit,
    onInstallCppFromServer: () -> Unit,
    onInstallPythonFromServer: () -> Unit,
    onInstallRustFromServer: () -> Unit,
    onInstallGnuLanguagesFromServer: () -> Unit,
    onInstallWebFromServer: () -> Unit,
    onInstallGitFromServer: () -> Unit,
    onInstallFromFile: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenCppDetails: () -> Unit,
    onOpenPythonDetails: () -> Unit,
    onOpenRustDetails: () -> Unit,
    onOpenGnuLanguagesDetails: () -> Unit,
    onOpenWebDetails: () -> Unit,
    onOpenGitDetails: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ExtensionBackground).verticalScroll(rememberScrollState())) {
        ExtensionListRow(
            icon = "C+",
            iconColor = Color(0xFF3478C6),
            name = "C/C++",
            description = "Clang 21 compiler and Android C++ runtime",
            installed = cppInfo != null,
            busy = busy,
            onClick = onOpenCppDetails,
            onInstall = onInstallFromFile,
        )
        ExtensionListRow(
            icon = "Py",
            iconColor = Color(0xFF3776AB),
            name = "Python",
            description = "CPython 3.14, Jedi completion and diagnostics",
            installed = pythonInfo != null,
            busy = busy,
            onClick = onOpenPythonDetails,
            onInstall = onInstallFromFile,
        )
        ExtensionListRow(
            icon = "Rs",
            iconColor = Color(0xFFB7410E),
            name = "Rust",
            description = "rustc, Cargo, rust-analyzer and Pico targets",
            installed = rustInfo != null,
            busy = busy,
            onClick = onOpenRustDetails,
            onInstall = onInstallFromFile,
        )
        ExtensionListRow(
            icon = "GNU",
            iconColor = Color(0xFF5B7F3B),
            name = "Fortran & COBOL",
            description = "GNU Fortran and GnuCOBOL compilers",
            installed = gnuLanguagesInfo != null,
            busy = busy,
            onClick = onOpenGnuLanguagesDetails,
            onInstall = onInstallFromFile,
        )
        ExtensionListRow(
            icon = "Web",
            iconColor = Color(0xFFE7A328),
            name = "Web Development",
            description = "Node.js, npm, JavaScript and TypeScript tools",
            installed = webInfo != null,
            busy = busy,
            onClick = onOpenWebDetails,
            onInstall = onInstallFromFile,
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenDetails).padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.size(38.dp).background(Color(0xFF6C4ED9)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("µ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Raspberry Pi Pico", color = ExtensionForeground, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    "Offline RP2040, RP2350, Pico W and RISC-V tools",
                    color = ExtensionMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("FoldCode", color = Color(0xFF6CB6FF), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            if (picoInfo == null) {
                Button(
                    onClick = onInstallFromFile,
                    enabled = !busy,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ExtensionAccent),
                ) { Text(if (busy) "…" else "From file", fontSize = 10.sp) }
            } else {
                Text("✓", color = Color(0xFF82C99A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        notice?.let {
            Text(it, color = if (it.startsWith("Installed")) Color(0xFF82C99A) else Color(0xFFFFB4A9), fontSize = 9.sp, modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
internal fun WebExtensionDetails(
    info: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstall: () -> Unit,
    onInstallFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.size(68.dp).background(Color(0xFFE7A328)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Web", color = Color(0xFF17191D), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("Web Development", color = ExtensionForeground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("FoldCode · Node.js · npm · JavaScript · TypeScript", color = Color(0xFF6CB6FF), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text("Build and run modern web projects locally on Android.", color = ExtensionForeground, fontSize = 11.sp)
            }
        }
        ExtensionActions(info, busy, onInstallFromFile, onUninstall)
        notice?.let { Text(it, color = ExtensionMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
        Text("Offline web tooling", color = ExtensionForeground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The extension supplies an Android ARM64 Node.js runtime, npm, npx and Microsoft's V8 Debug Adapter. It supports JavaScript and TypeScript projects, package.json scripts, local development servers and Node breakpoints without placing the runtime in the base app.",
            color = ExtensionForeground,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        ExtensionSection("Languages", "JavaScript · TypeScript · JSX/TSX · HTML · CSS · JSON")
        ExtensionSection("Runtime", "Node.js · npm · npx · package.json scripts")
        ExtensionSection("Intelligence", "Syntax coloring now; TypeScript language-server completion and diagnostics are delivered with the runtime package")
        ExtensionSection("Debugging", "V8 breakpoints, stepping, call stack, variables and debug console for Node.js programs")
        ExtensionSection("Preview", "Run a local development server and open its localhost URL on the device")
        ExtensionSection("Dependencies", "npm packages remain inside the project so they can be reused offline")
    }
}

@Composable
internal fun GnuLanguagesExtensionDetails(
    info: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstall: () -> Unit,
    onInstallFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.size(68.dp).background(Color(0xFF5B7F3B)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("GNU", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("Fortran & COBOL", color = ExtensionForeground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("FoldCode · GNU Fortran · GnuCOBOL · Android ARM64", color = Color(0xFF6CB6FF), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text("Compile, run and navigate modern Fortran and COBOL projects completely offline.", color = ExtensionForeground, fontSize = 11.sp)
            }
        }
        ExtensionActions(info, busy, onInstallFromFile, onUninstall)
        notice?.let { Text(it, color = ExtensionMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
        Text("GNU language development", color = ExtensionForeground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The extension contains a shared GNU/Linux ARM64 runtime, GNU Fortran and GnuCOBOL. Projects compile locally on the phone and console input/output remains available in FoldCode's terminal.",
            color = ExtensionForeground,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        ExtensionSection("Fortran", "Fortran 2018 files: .f, .for, .f77, .f90, .f95, .f03, .f08 and .f18")
        ExtensionSection("COBOL", "Free-form and traditional GnuCOBOL projects: .cob, .cbl and copybooks")
        ExtensionSection("Intelligence", "Project-wide modules, procedures, derived types, data items, paragraphs, context-aware completion and live structural diagnostics")
        ExtensionSection("Offline", "Compiler, standard runtime libraries and linker tools are installed with the extension")
    }
}

@Composable
internal fun RustExtensionDetails(
    info: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstall: () -> Unit,
    onInstallFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.size(68.dp).background(Color(0xFFB7410E)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Rs", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("Rust", color = ExtensionForeground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("FoldCode · Cargo · rust-analyzer · Android ARM64", color = Color(0xFF6CB6FF), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text("Rust development and Pico firmware builds on Android.", color = ExtensionForeground, fontSize = 11.sp)
            }
        }
        ExtensionActions(info, busy, onInstallFromFile, onUninstall)
        notice?.let { Text(it, color = ExtensionMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
        Text("Rust development", color = ExtensionForeground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The extension supplies rustc, Cargo, rustfmt, rust-src, persistent offline rust-analyzer intelligence and the three Pico compilation targets. RP2040/RP2350 UF2 packaging and USB deployment are provided by the Raspberry Pi Pico extension through official picotool.",
            color = ExtensionForeground,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        ExtensionSection("Intelligence", "rust-analyzer completion, diagnostics, hover information and Cargo project discovery")
        ExtensionSection("Pico targets", "RP2040 Arm · RP2350 Arm · RP2350 RISC-V target libraries")
        ExtensionSection("Dependencies", "Cargo registry and Git dependencies are cached inside the project for offline reuse")
    }
}

@Composable
internal fun PythonExtensionDetails(
    info: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstall: () -> Unit,
    onInstallFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.size(68.dp).background(Color(0xFF3776AB)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Py", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("Python", color = ExtensionForeground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("FoldCode · CPython 3.14 · Android ARM64", color = Color(0xFF6CB6FF), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text("Run Python projects and edit Pico MicroPython with offline completion.", color = ExtensionForeground, fontSize = 11.sp)
            }
        }
        ExtensionActions(info, busy, onInstallFromFile, onUninstall)
        notice?.let { Text(it, color = ExtensionMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
        Text("Offline Python tooling", color = ExtensionForeground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The extension contains the official Android CPython runtime, standard library, pip, Jedi project intelligence and live syntax diagnostics. The same intelligence understands MicroPython machine, rp2 and network APIs; board firmware and USB deployment remain optional components of the Raspberry Pi Pico extension.",
            color = ExtensionForeground,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        ExtensionSection("Runtime", "CPython 3.14 · pip · standard library · hashlib/SSL/SQLite support")
        ExtensionSection("Intelligence", "Jedi project/type completion, live syntax diagnostics, plus MicroPython machine, Pin, ADC, I2C, SPI, PWM, UART, Timer, rp2 and network APIs")
        ExtensionSection("Dependencies", "python -m pip install --target .foldcode/python <package-or-git-url>")
        ExtensionSection("Build scripts", "CMake, custom commands and generated source/header workflows")
        ExtensionSection("Offline", "No network is required after installation unless the project downloads its own dependencies.")
    }
}

@Composable
internal fun CppExtensionDetails(
    info: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstall: () -> Unit,
    onInstallFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.size(68.dp).background(Color(0xFF3478C6)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("C+", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("C/C++", color = ExtensionForeground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("FoldCode · Clang 21 · Android ARM64", color = Color(0xFF6CB6FF), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text("Compile and run C and C++ projects directly on the phone.", color = ExtensionForeground, fontSize = 11.sp)
            }
        }
        ExtensionActions(info, busy, onInstallFromFile, onUninstall)
        notice?.let { Text(it, color = ExtensionMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(24.dp))
        Text("On-device C/C++ development", color = ExtensionForeground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The base FoldCode installation is an editor. This extension downloads the LLVM/Clang dependency libraries, compiler resources and Android sysroot from your configured FoldCode server. Small Android-labelled launchers remain in the host so the downloaded tools can run under Android's execution policy.",
            color = ExtensionForeground,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        ExtensionSection("Language", "C++20 · multiple translation units · headers · compiler diagnostics")
        ExtensionSection("Runtime", "Clang 21 · LLVM LLD · Android ARM64 libc++ · direct terminal execution")
        ExtensionSection("Git libraries", "Clone source dependencies for direct Clang commands, custom scripts, Make/Ninja or CMake. Prebuilt libraries must match the target architecture.")
        ExtensionSection("Dependency", "Required automatically by the Raspberry Pi Pico extension.")
    }
}

@Composable
internal fun GitExtensionDetails(
    info: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstall: () -> Unit,
    onInstallFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.size(68.dp).background(Color(0xFFF4513A)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Git", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("Git", color = ExtensionForeground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Built into FoldCode · Git 2.55.0 · Android ARM64", color = Color(0xFF6CB6FF), fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text("Source control, repository cloning and project dependency transport.", color = ExtensionForeground, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Git tooling", color = ExtensionForeground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Git is a base FoldCode service, not an extension. Source Control, the terminal, CMake FetchContent and project dependencies all share the same native engine.",
            color = ExtensionForeground,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        ExtensionSection("Projects", "Repositories, branches, commits, remotes and submodules")
        ExtensionSection("Dependencies", "Source libraries and package sources used by Python, direct compiler commands, Make, Ninja, CMake or custom scripts")
        ExtensionSection("Offline", "Previously cloned repositories remain available without a network connection.")
    }
}

@Composable
private fun ExtensionActions(
    info: FoldCodeExtensionInfo?,
    busy: Boolean,
    onInstallFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Spacer(Modifier.height(14.dp))
    if (info == null) {
        Button(
            onClick = onInstallFromFile,
            enabled = !busy,
            modifier = Modifier.height(38.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ExtensionAccent),
        ) {
            Text(if (busy) "Installing…" else "From file", fontSize = 11.sp)
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (info.installedBytes > 0L) "Installed · ${formatBytes(info.installedBytes)}" else "Installed",
                color = Color(0xFF82C99A),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onUninstall, enabled = !busy, modifier = Modifier.height(36.dp)) {
                Text("Uninstall", fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ExtensionListRow(
    icon: String,
    iconColor: Color,
    name: String,
    description: String,
    installed: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.size(38.dp).background(iconColor),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { Text(icon, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = ExtensionForeground, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(description, color = ExtensionMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("FoldCode", color = Color(0xFF6CB6FF), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(6.dp))
        if (installed) {
            Text(
                "✓",
                color = Color(0xFF82C99A),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(5.dp),
            )
        } else {
            Button(
                onClick = onInstall,
                enabled = !busy,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ExtensionAccent),
            ) { Text(if (busy) "…" else "From file", fontSize = 10.sp) }
        }
    }
}

@Composable
internal fun PicoExtensionDetails(
    picoInfo: FoldCodeExtensionInfo?,
    gnuArmInfo: FoldCodeExtensionInfo?,
    busy: Boolean,
    notice: String?,
    serverConfigured: Boolean,
    onInstall: () -> Unit,
    onInstallFromFile: () -> Unit,
    onPrepareComponents: (Set<String>) -> Unit,
    onDeleteComponents: (Set<String>) -> Unit,
    onUninstall: () -> Unit,
    onInstallGnuArm: () -> Unit,
    onUninstallGnuArm: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf("DETAILS") }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        val wide = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 28.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    Modifier.size(if (wide) 84.dp else 62.dp).background(Color(0xFF6C4ED9)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { Text("µ", color = Color.White, fontSize = if (wide) 50.sp else 36.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(if (wide) 20.dp else 13.dp))
                Column(Modifier.weight(1f)) {
                    Text("Raspberry Pi Pico", color = ExtensionForeground, fontSize = if (wide) 23.sp else 18.sp, fontWeight = FontWeight.Bold)
                    Text("FoldCode  ✓  ·  Official Pico SDK ${PicoSdkRelease.sdkVersion}", color = Color(0xFF6CB6FF), fontSize = 11.sp)
                    Spacer(Modifier.height(7.dp))
                    Text("Native offline development for Raspberry Pi microcontrollers on Android.", color = ExtensionForeground, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    if (picoInfo == null) {
                        Button(
                            onClick = onInstallFromFile,
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = ExtensionAccent),
                        ) {
                            Text(if (busy) "Installing…" else "From file", fontSize = 11.sp)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (picoInfo.installedBytes > 0L) "Installed · ${formatBytes(picoInfo.installedBytes)}" else "Installed",
                                color = Color(0xFF82C99A),
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(onClick = onUninstall, enabled = !busy, modifier = Modifier.height(34.dp)) {
                                Text("Uninstall", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            notice?.let { Text(it, color = ExtensionMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp)) }
            if (picoInfo != null) {
                Spacer(Modifier.height(16.dp))
                Text("Offline components", color = ExtensionForeground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Components included in a local extension file are installed automatically. Targets missing from the file require a separate package or extension server.",
                    color = ExtensionMuted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(6.dp))
                ExtensionComponentRow(
                    name = "GNU Arm GCC 15.2",
                    description = "Optional compiler for GCC-specific projects and plugins",
                    installed = gnuArmInfo != null,
                    busy = busy,
                    onInstall = onInstallGnuArm,
                    onDelete = onUninstallGnuArm,
                )
                picoInfo.components.forEach { component ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                component.installed -> "⌫"
                                component.availableOffline -> "+"
                                else -> "⇩"
                            },
                            color = if (component.installed) Color(0xFFFF8A80) else Color(0xFF6CB6FF),
                            fontSize = 18.sp,
                            modifier = Modifier.clickable(enabled = !busy) {
                                if (component.installed) onDeleteComponents(setOf(component.id))
                                else onPrepareComponents(setOf(component.id))
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Text(component.name, color = ExtensionForeground, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                component.installed -> "Installed"
                                component.availableOffline -> "Ready to install"
                                else -> "Download required"
                            },
                            color = if (component.installed) Color(0xFF82C99A) else ExtensionMuted,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("DETAILS", "FEATURES").forEach { tab ->
                    Column(Modifier.clickable { selectedTab = tab }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(tab, color = if (selectedTab == tab) ExtensionForeground else ExtensionMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Spacer(Modifier.width(55.dp).height(2.dp).background(if (selectedTab == tab) ExtensionAccent else Color.Transparent))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (selectedTab == "DETAILS") {
                Text("Raspberry Pi Pico development", color = ExtensionForeground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text(
                    "Create, compile and flash Pico projects entirely on the phone. The extension uses one shared Pico SDK with small board profiles, avoiding repeated headers and sources for every target.",
                    color = ExtensionForeground,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(15.dp))
                ExtensionSection("Supported targets", "Raspberry Pi Pico (RP2040)\nRaspberry Pi Pico W\nRaspberry Pi Pico 2 / Pico 2 W (RP2350 Arm)\nRaspberry Pi Pico 2 (RP2350 RISC-V)")
                ExtensionSection("Tooling", "Official Pico SDK 2.3.0 · GNU Arm 15.2.1 · GNU RISC-V runtime · Android-native pioasm · TinyUSB · CYW43/lwIP")
                ExtensionSection("Storage", "Shared SDK and toolchain payloads are extracted only when needed. Consumed compressed archives are removed automatically.")
            } else {
                ExtensionSection("Build", "Component static libraries\nCMake target_link_libraries resolver\nC, C++ and GNU assembly (.s, .S, .asm)\nUF2 and ELF generation\nCompiler diagnostics")
                ExtensionSection("Assembly editing", "Arm and RISC-V syntax coloring\nBoard-aware instruction and register completion\nLive diagnostics from the configured CMake compile command")
                ExtensionSection("Hardware", "GPIO · UART · I²C · SPI · PWM · interrupts · DMA · PIO · multicore")
                ExtensionSection("Device workflow", "One-click BOOTSEL detection and USB flashing with terminal progress.")
            }
        }
    }
}

@Composable
private fun ExtensionComponentRow(
    name: String,
    description: String,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (installed) "⌫" else "⇩",
            color = if (installed) Color(0xFFFF8A80) else Color(0xFF6CB6FF),
            fontSize = 18.sp,
            modifier = Modifier.clickable(enabled = !busy) { if (installed) onDelete() else onInstall() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(name, color = ExtensionForeground, fontSize = 11.sp)
            Text(description, color = ExtensionMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (installed) "Installed" else "From file", color = if (installed) Color(0xFF82C99A) else ExtensionMuted, fontSize = 9.sp)
    }
}

@Composable
private fun ExtensionSection(title: String, body: String) {
    Text(title, color = ExtensionForeground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(5.dp))
    Text(body, color = ExtensionMuted, fontSize = 11.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(14.dp))
}

@Composable
internal fun PicoExtensionRequiredPanel(onOpenExtensions: () -> Unit) {
    Column(Modifier.fillMaxSize().background(ExtensionBackground).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        Text("Raspberry Pi Pico extension", color = ExtensionForeground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Install the Pico toolchain package to create, compile and flash Pico projects.", color = ExtensionMuted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenExtensions, colors = ButtonDefaults.buttonColors(containerColor = ExtensionAccent)) { Text("Open Extensions") }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}
