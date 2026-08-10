package dev.foldcode.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.BasicTextField

private val PicoForeground = Color(0xFFD8DEE9)
private val PicoMuted = Color(0xFF89909E)
private val PicoAccent = Color(0xFF5B8DEF)
private val PicoPanelBackground = Color(0xFF202329)
private val PicoField = Color(0xFF2A2E35)

@Composable
internal fun PicoQuickAccessPanel(
    picoProjectOpen: Boolean,
    rustProjectOpen: Boolean,
    microPythonProjectOpen: Boolean,
    configuration: PicoProjectConfiguration,
    cmakeAnalysis: PicoCMakeAnalysis,
    pythonAvailable: Boolean,
    rustAvailable: Boolean,
    building: Boolean,
    cleaning: Boolean,
    onCreateProject: (String, PicoBoard, PicoProjectLanguage) -> Unit,
    onCreateExample: (String, PicoBoard, PicoExample, PicoProjectLanguage) -> Unit,
    onImportProject: () -> Unit,
    onCompile: () -> Unit,
    onStop: () -> Unit,
    onRunUsb: () -> Unit,
    onClean: () -> Unit,
    onConfigureCMake: () -> Unit,
    onConfigure: (PicoProjectConfiguration) -> Unit,
    onManageComponents: () -> Unit,
    onOpenHardwareApis: () -> Unit,
    onOpenHighLevelApis: () -> Unit,
    onOpenNetworkingLibraries: () -> Unit,
    onOpenRuntimeInfrastructure: () -> Unit,
    onOpenSdkReference: () -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showMicroPythonDialog by rememberSaveable { mutableStateOf(false) }
    var showRustDialog by rememberSaveable { mutableStateOf(false) }
    var showExampleDialog by rememberSaveable { mutableStateOf(false) }
    var showConfigureDialog by rememberSaveable { mutableStateOf(false) }
    if (showCreateDialog) {
        NewPicoProjectDialog(
            exampleMode = false,
            fixedLanguage = null,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, board, _, language ->
                showCreateDialog = false
                onCreateProject(name, board, language)
            },
        )
    }
    if (showMicroPythonDialog) {
        NewPicoProjectDialog(
            exampleMode = false,
            fixedLanguage = PicoProjectLanguage.MicroPython,
            onDismiss = { showMicroPythonDialog = false },
            onCreate = { name, board, _, language ->
                showMicroPythonDialog = false
                onCreateProject(name, board, language)
            },
        )
    }
    if (showRustDialog) {
        NewPicoProjectDialog(
            exampleMode = false,
            fixedLanguage = PicoProjectLanguage.Rust,
            onDismiss = { showRustDialog = false },
            onCreate = { name, board, _, language ->
                showRustDialog = false
                onCreateProject(name, board, language)
            },
        )
    }
    if (showExampleDialog) {
        NewPicoProjectDialog(
            exampleMode = true,
            fixedLanguage = null,
            onDismiss = { showExampleDialog = false },
            onCreate = { name, board, example, language ->
                showExampleDialog = false
                onCreateExample(name, board, example, language)
            },
        )
    }
    if (showConfigureDialog) {
        PicoBuildConfigurationDialog(
            configuration = configuration,
            analysis = cmakeAnalysis,
            rustProject = rustProjectOpen,
            onDismiss = { showConfigureDialog = false },
            onSave = {
                showConfigureDialog = false
                onConfigure(it)
            },
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
        PicoSection("General") {
            PicoAction("▱+", "New C/C++ Project") { showCreateDialog = true }
            PicoAction(
                "▱+",
                "New MicroPython Project",
                enabled = pythonAvailable,
                suffix = if (pythonAvailable) "Python intelligence · Pico deployment component" else "Install Python extension",
            ) { showMicroPythonDialog = true }
            PicoAction(
                "▱+",
                "New Rust Project",
                enabled = rustAvailable,
                suffix = if (rustAvailable) "Cargo · rust-analyzer · Pico targets" else "Install Rust extension",
            ) { showRustDialog = true }
            PicoAction("⇩", "Import Project", onClick = onImportProject)
            PicoAction("▱↗", "New Project From Example") { showExampleDialog = true }
            PicoAction(
                "⬡",
                "Manage Components",
                suffix = "SDK, boards, compilers and offline tools",
                onClick = onManageComponents,
            )
        }
        PicoSection("Project") {
            if (building) {
                PicoAction("■", "Stop Compile", enabled = picoProjectOpen, onClick = onStop)
            } else {
                PicoAction("01", "Compile Project", enabled = picoProjectOpen && !cleaning, onClick = onCompile)
            }
            PicoAction("▷", "Run Project (USB)", enabled = picoProjectOpen && !building && !cleaning, suffix = "Auto-detect BOOTSEL Pico and flash UF2", onClick = onRunUsb)
            PicoAction(
                "♙",
                "Build Configuration",
                enabled = picoProjectOpen && !rustProjectOpen && !microPythonProjectOpen,
                suffix = when {
                    rustProjectOpen -> "Cargo · ${configuration.board.platform.uppercase()}"
                    microPythonProjectOpen -> "MicroPython · ${configuration.board.displayName}"
                    else -> "${PicoSdkRelease.displayName} · ${configuration.board.platform.uppercase()} · Full CMake"
                },
            ) { showConfigureDialog = true }
            PicoAction(
                "◇",
                if (rustProjectOpen) "Configure Cargo" else "Configure CMake",
                enabled = picoProjectOpen && !microPythonProjectOpen && !building && !cleaning,
                onClick = onConfigureCMake,
            )
            if (cleaning) {
                PicoAction("…", if (rustProjectOpen) "Cleaning Cargo…" else "Cleaning and Reconfiguring…", enabled = false) {}
            } else {
                PicoAction(
                    "♻",
                    if (rustProjectOpen) "Clean Cargo" else "Clean CMake",
                    enabled = picoProjectOpen && !microPythonProjectOpen && !building,
                    onClick = onClean,
                )
            }
            PicoAction(
                "⚙",
                "Build Type",
                enabled = picoProjectOpen && !microPythonProjectOpen && !building && !cleaning,
                suffix = configuration.buildType.displayName,
            ) { showConfigureDialog = true }
            PicoAction(
                "▣",
                "Board",
                enabled = picoProjectOpen && !rustProjectOpen && !microPythonProjectOpen && !building && !cleaning,
                suffix = configuration.board.displayName,
            ) { showConfigureDialog = true }
        }
        PicoSection("Documentation") {
            PicoAction("◉", "Hardware APIs", suffix = "GPIO · buses · DMA · PIO · timers", onClick = onOpenHardwareApis)
            PicoAction("◇", "High Level APIs", suffix = "time · sync · multicore · flash · status LED", onClick = onOpenHighLevelApis)
            PicoAction("▤", "Networking Libraries", suffix = "CYW43 · lwIP · BTstack", onClick = onOpenNetworkingLibraries)
            PicoAction("⚙", "Runtime Infrastructure", suffix = "boot · runtime · C library · arithmetic", onClick = onOpenRuntimeInfrastructure)
            PicoAction("☰", "Pico SDK Reference", suffix = "Complete official SDK documentation", onClick = onOpenSdkReference)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            when {
                !picoProjectOpen -> "Create or open a Pico project to enable its tools."
                rustProjectOpen -> "Current project targets ${configuration.board.platform.uppercase()} using Cargo."
                microPythonProjectOpen -> "Current project targets ${configuration.board.displayName} using MicroPython."
                else -> "Current project is configured for ${configuration.board.platform.uppercase()} using Full CMake."
            },
            color = PicoMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun PicoBuildConfigurationDialog(
    configuration: PicoProjectConfiguration,
    analysis: PicoCMakeAnalysis,
    rustProject: Boolean,
    onDismiss: () -> Unit,
    onSave: (PicoProjectConfiguration) -> Unit,
) {
    var board by rememberSaveable { mutableStateOf(configuration.board) }
    var target by rememberSaveable(configuration.target) { mutableStateOf(configuration.target.orEmpty()) }
    var networkPolicy by rememberSaveable { mutableStateOf(configuration.networkPolicy) }
    var buildType by rememberSaveable { mutableStateOf(configuration.buildType) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PicoPanelBackground).padding(18.dp).verticalScroll(rememberScrollState())) {
            Text(if (rustProject) "Configure Cargo Build" else "Configure Pico Build", color = PicoForeground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (!rustProject) {
                Spacer(Modifier.height(8.dp))
                Text("Board", color = PicoMuted, fontSize = 10.sp)
                PicoBoard.entries.forEach { option ->
                    Text(
                        "${if (board == option) "●" else "○"}  ${option.displayName}",
                        color = if (board == option) PicoForeground else PicoMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { board = option }.padding(vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Build system", color = PicoMuted, fontSize = 10.sp)
            Text(if (rustProject) "●  Cargo" else "●  Full Pico CMake", color = PicoForeground, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
            Spacer(Modifier.height(8.dp))
            Text("Build type", color = PicoMuted, fontSize = 10.sp)
            val buildTypes = if (rustProject) listOf(PicoBuildType.Debug, PicoBuildType.Release) else PicoBuildType.entries
            buildTypes.forEach { option ->
                Text(
                    "${if (buildType == option) "●" else "○"}  ${option.displayName}",
                    color = if (buildType == option) PicoForeground else PicoMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable { buildType = option }.padding(vertical = 5.dp),
                )
            }
            if (!rustProject && analysis.targets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Build target", color = PicoMuted, fontSize = 10.sp)
                Text(
                    "${if (target.isBlank()) "●" else "○"}  All default targets",
                    color = if (target.isBlank()) PicoForeground else PicoMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable { target = "" }.padding(vertical = 5.dp),
                )
                analysis.targets.forEach { option ->
                    Text(
                        "${if (target == option) "●" else "○"}  $option",
                        color = if (target == option) PicoForeground else PicoMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { target = option }.padding(vertical = 5.dp),
                    )
                }
            }
            if (!rustProject) {
                Spacer(Modifier.height(8.dp))
                Text("Dependency network", color = PicoMuted, fontSize = 10.sp)
                PicoDependencyNetworkPolicy.entries.forEach { option ->
                    Text(
                        "${if (networkPolicy == option) "●" else "○"}  ${option.displayName}",
                        color = if (networkPolicy == option) PicoForeground else PicoMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { networkPolicy = option }.padding(vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = PicoMuted) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onSave(
                            configuration.copy(
                                board = if (rustProject) configuration.board else board,
                                target = if (rustProject) configuration.target else target.ifBlank { null },
                                networkPolicy = if (rustProject) configuration.networkPolicy else networkPolicy,
                                buildType = buildType,
                            ),
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PicoAccent),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun PicoSection(title: String, content: @Composable () -> Unit) {
    Text("⌄  $title", color = PicoForeground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
    content()
}

@Composable
private fun PicoAction(
    glyph: String,
    label: String,
    enabled: Boolean = true,
    suffix: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (suffix == null) 38.dp else 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = if (enabled) PicoForeground else PicoMuted.copy(alpha = 0.55f), fontSize = 15.sp, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (enabled) PicoForeground else PicoMuted.copy(alpha = 0.55f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            suffix?.let {
                Text(
                    it,
                    color = PicoMuted.copy(alpha = if (enabled) 0.9f else 0.55f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NewPicoProjectDialog(
    exampleMode: Boolean,
    fixedLanguage: PicoProjectLanguage?,
    onDismiss: () -> Unit,
    onCreate: (String, PicoBoard, PicoExample, PicoProjectLanguage) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(if (exampleMode) "PICO_EXAMPLE" else "PICO_PROJECT") }
    var board by rememberSaveable { mutableStateOf(PicoBoard.Pico) }
    var example by rememberSaveable { mutableStateOf(PicoExample.Blink) }
    var language by rememberSaveable { mutableStateOf(fixedLanguage ?: PicoProjectLanguage.Cpp) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(PicoPanelBackground)
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                when {
                    exampleMode -> "New Project From Example"
                    fixedLanguage == PicoProjectLanguage.MicroPython -> "New Pico MicroPython Project"
                    fixedLanguage == PicoProjectLanguage.Rust -> "New Pico Rust Project"
                    else -> "New Raspberry Pi Pico Project"
                },
                color = PicoForeground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    exampleMode -> "Choose a board and an official-style starter example."
                    fixedLanguage == PicoProjectLanguage.MicroPython ->
                        "Creates a MicroPython board project with main.py in FoldCode/Projects."
                    fixedLanguage == PicoProjectLanguage.Rust ->
                        "Creates a Cargo firmware project for the selected Pico board in FoldCode/Projects."
                    else -> "Creates an empty, buildable C or C++ Full CMake project in FoldCode/Projects."
                },
                color = PicoMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text("Project name", color = PicoMuted, fontSize = 10.sp)
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = PicoForeground, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().background(PicoField).padding(11.dp),
            )
            if (exampleMode) {
                Spacer(Modifier.height(8.dp))
                val availableExamples = PicoExample.entries.filter { it.supports(language) }
                PicoDropdown(
                    label = "Example",
                    selected = example,
                    options = availableExamples,
                    displayName = PicoExample::displayName,
                    onSelected = { example = it },
                )
                Text(
                    example.description,
                    color = PicoMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                PicoDropdown(
                    label = "Language",
                    selected = language,
                    options = listOf(PicoProjectLanguage.C, PicoProjectLanguage.Cpp),
                    displayName = PicoProjectLanguage::displayName,
                    onSelected = { selectedLanguage ->
                        language = selectedLanguage
                        if (!example.supports(selectedLanguage)) {
                            example = PicoExample.entries.first { it.supports(selectedLanguage) }
                        }
                    },
                )
                PicoDropdown(
                    label = "Board",
                    selected = board,
                    options = PicoBoard.entries,
                    displayName = PicoBoard::displayName,
                    onSelected = { board = it },
                )
            } else {
                if (fixedLanguage == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Language", color = PicoMuted, fontSize = 10.sp)
                    listOf(PicoProjectLanguage.C, PicoProjectLanguage.Cpp).forEach { option ->
                        Text(
                            "${if (language == option) "●" else "○"}  ${option.displayName}",
                            color = if (language == option) PicoForeground else PicoMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().clickable { language = option }.padding(vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Board", color = PicoMuted, fontSize = 10.sp)
                PicoBoard.entries.forEach { option ->
                    Text(
                        "${if (board == option) "●" else "○"}  ${option.displayName}",
                        color = if (board == option) PicoForeground else PicoMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { board = option }.padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = PicoMuted) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onCreate(name.trim(), board, example, language) },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = PicoAccent),
                ) { Text("Create") }
            }
        }
    }
}

@Composable
private fun <T> PicoDropdown(
    label: String,
    selected: T,
    options: List<T>,
    displayName: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(label, color = PicoMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(PicoField)
                .clickable { expanded = true }
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                displayName(selected),
                color = PicoForeground,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("⌄", color = PicoMuted, fontSize = 14.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(PicoPanelBackground),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            displayName(option),
                            color = if (option == selected) PicoForeground else PicoMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Text(if (option == selected) "●" else "○", color = PicoMuted, fontSize = 10.sp)
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}
