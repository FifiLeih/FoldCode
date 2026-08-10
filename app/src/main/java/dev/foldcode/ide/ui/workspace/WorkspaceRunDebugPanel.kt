package dev.foldcode.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RunDebugPanel(
    projectName: String,
    files: List<ProjectFile>,
    building: Boolean,
    picoProject: Boolean,
    actions: RunDebugActions,
) {
    val paths = remember(files) { files.map(ProjectFile::name) }
    val runtime = remember(paths, picoProject) {
        when {
            picoProject && paths.any { it.endsWith(".rs", true) } -> "Raspberry Pi Pico · Rust"
            picoProject && paths.any { it.endsWith(".py", true) } -> "Raspberry Pi Pico · MicroPython"
            picoProject -> "Raspberry Pi Pico · C/C++"
            paths.any { it.endsWith(".py", true) } -> "Python"
            paths.any { it.endsWith("Cargo.toml", true) || it.endsWith(".rs", true) } -> "Rust"
            paths.any(::isFortranSource) -> "Fortran"
            paths.any(::isCobolSource) -> "COBOL"
            paths.any { it.endsWith(".c", true) || it.endsWith(".cpp", true) || it.endsWith(".cc", true) } -> "C/C++"
            else -> "No runnable configuration"
        }
    }
    val debugSupported = runtime != "No runnable configuration" && runtime != "Raspberry Pi Pico · MicroPython"
    val snapshot = actions.snapshot
    val sessionVisible = snapshot.status != DebugSessionStatus.Idle
    val sessionActive = snapshot.status == DebugSessionStatus.Starting ||
        snapshot.status == DebugSessionStatus.Running ||
        snapshot.status == DebugSessionStatus.Paused
    var expression by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("RUN AND DEBUG", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text(projectName, color = Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(runtime, color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = if (building) actions.stop else actions.run,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = if (building) Color(0xFFB84A4A) else Accent),
        ) {
            Text(if (building) "■  Stop" else "▷  Run", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = actions.debug,
            enabled = !building && debugSupported && !sessionActive,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = EditorTab, contentColor = Foreground),
        ) {
            Text("◇  Start Debugging")
        }
        if (sessionVisible) {
            Spacer(Modifier.height(16.dp))
            DebugSectionTitle("SESSION")
            Text(snapshot.title, color = Foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${snapshot.status.name.uppercase()} · ${snapshot.message}",
                color = if (snapshot.status == DebugSessionStatus.Failed) Color(0xFFFF8A80) else Accent,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )

            DebugSectionTitle("BREAKPOINTS")
            if (snapshot.breakpoints.isEmpty()) {
                Text("Tap beside a line number to add a breakpoint.", color = Muted, fontSize = 10.sp)
            } else snapshot.breakpoints.forEach { point ->
                Text(
                    "${if (point.verified) "●" else "○"}  ${point.file}:${point.line}",
                    color = if (point.verified) Color(0xFFFF6B6B) else Muted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            DebugSectionTitle("CALL STACK")
            if (snapshot.frames.isEmpty()) {
                Text(if (snapshot.status == DebugSessionStatus.Paused) "Loading frames…" else "Pause at a breakpoint to inspect frames.", color = Muted, fontSize = 10.sp)
            } else snapshot.frames.forEach { frame ->
                val active = frame.id == snapshot.activeFrameId
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (active) EditorTab else Color.Transparent)
                        .clickable { actions.selectFrame(frame.id) }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    Text(
                        if (frame.external) "Android · ${frame.name}" else frame.name,
                        color = if (frame.external) Muted else Foreground,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${frame.file?.substringAfterLast('/') ?: if (frame.external) "system runtime" else "unknown"}:${frame.line}",
                        color = Muted,
                        fontSize = 9.sp,
                    )
                }
            }

            DebugSectionTitle("VARIABLES")
            if (snapshot.variables.isEmpty()) {
                Text("No variables available.", color = Muted, fontSize = 10.sp)
            } else snapshot.variables.forEach { variable ->
                DebugVariableRow(variable, depth = 0, onToggle = actions.toggleVariable)
            }

            DebugSectionTitle("DEBUG CONSOLE")
            Text(
                snapshot.console.ifBlank { "Debugger output will appear here." },
                color = if (snapshot.console.isBlank()) Muted else Foreground,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 180.dp).background(Background).padding(7.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = expression,
                    onValueChange = { expression = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Foreground, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.weight(1f).background(EditorTab).padding(7.dp),
                    decorationBox = { field ->
                        if (expression.isBlank()) Text("Evaluate expression", color = Muted, fontSize = 10.sp)
                        field()
                    },
                )
                Text(
                    "SEND",
                    color = Accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = expression.isNotBlank()) {
                        actions.evaluate(expression)
                        expression = ""
                    }.padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        Spacer(Modifier.height(14.dp))
        Text("DEBUG CONFIGURATION", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(9.dp))
        Text(
            when {
                runtime == "Python" -> "Runs python -m pdb with interactive commands in Terminal. Use n, s, c, p and q to navigate."
                runtime == "Raspberry Pi Pico · MicroPython" -> "Run deploys the script and MicroPython firmware. On-device MicroPython debugging is not available yet."
                picoProject -> "Builds unoptimized firmware with symbols, then attaches through an installed OpenOCD SWD transport."
                runtime == "Rust" -> "Uses Cargo's debug profile. Android-hosted LLDB requires the Rust Android debug target component."
                runtime == "C/C++" -> "Builds with -O0 and -g, then launches the extension-delivered LLDB adapter."
                runtime in setOf("Fortran", "COBOL") -> "Builds with debug symbols. Native stepping depends on a compatible debugger backend."
                else -> "Open a supported project to create a launch configuration."
            },
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun DebugVariableRow(
    variable: DebugVariable,
    depth: Int,
    onToggle: (Int) -> Unit,
) {
    val expandable = variable.variablesReference > 0
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (depth * 12).dp, top = 2.dp, bottom = 2.dp)
                .clickable(enabled = expandable) { onToggle(variable.variablesReference) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                when {
                    !expandable -> " "
                    variable.expanded -> "▾"
                    else -> "▸"
                },
                color = if (expandable) Muted else Color.Transparent,
                fontSize = 10.sp,
                modifier = Modifier.width(14.dp),
            )
            Column(Modifier.weight(0.44f)) {
                Text(
                    variable.name,
                    color = Accent,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                variable.type?.takeIf(String::isNotBlank)?.let { type ->
                    Text(
                        type.replace("std::__ndk1::", "std::"),
                        color = Muted,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                variable.value,
                color = Foreground,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(0.56f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (variable.expanded) {
            if (variable.children.isEmpty()) {
                Text(
                    "No visible members",
                    color = Muted,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(start = ((depth + 1) * 12 + 14).dp, bottom = 3.dp),
                )
            } else variable.children.forEach { child ->
                DebugVariableRow(child, depth + 1, onToggle)
            }
        }
    }
}

@Composable
private fun DebugSectionTitle(value: String) {
    Spacer(Modifier.height(14.dp))
    Text(value, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}
