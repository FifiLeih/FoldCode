package dev.foldcode.ide

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

internal data class IdePreferences(
    val dexUiScale: Float = 1.25f,
    val theme: IdeTheme = IdeTheme.FoldCodeDark,
    val editorFontSizeSp: Float = 0f,
    val terminalFontSizeSp: Float = 0f,
    val tabWidth: Int = 4,
    val indentWithSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val autosaveDelayMs: Long = 700L,
    val buildJobs: Int = 0,
    val thermalAware: Boolean = true,
    val runShortcut: String = "F5",
    val saveShortcut: String = "Ctrl+S",
    val filesShortcut: String = "Ctrl+P",
    val terminalShortcut: String = "Ctrl+`",
    val sidebarShortcut: String = "Ctrl+B",
    val restoreTerminal: Boolean = true,
    val restoreSession: Boolean = true,
)

internal class IdePreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences("foldcode_appearance", Context.MODE_PRIVATE)

    fun load(): IdePreferences = IdePreferences(
        dexUiScale = preferences.getFloat("dex_ui_scale", 1.25f).coerceIn(1f, 1.75f),
        theme = runCatching {
            IdeTheme.valueOf(preferences.getString("theme", null) ?: IdeTheme.FoldCodeDark.name)
        }.getOrDefault(IdeTheme.FoldCodeDark),
        editorFontSizeSp = preferences.getFloat("editor_font_sp", 0f).coerceIn(0f, 24f),
        terminalFontSizeSp = preferences.getFloat("terminal_font_sp", 0f).coerceIn(0f, 24f),
        tabWidth = preferences.getInt("tab_width", 4).takeIf { it in setOf(2, 4, 8) } ?: 4,
        indentWithSpaces = preferences.getBoolean("indent_spaces", true),
        wordWrap = preferences.getBoolean("word_wrap", false),
        autosaveDelayMs = preferences.getLong("autosave_delay", 700L).coerceIn(0L, 5_000L),
        buildJobs = preferences.getInt("build_jobs", 0).coerceIn(0, 8),
        thermalAware = preferences.getBoolean("thermal_aware", true),
        runShortcut = preferences.getString("shortcut_run", "F5") ?: "F5",
        saveShortcut = preferences.getString("shortcut_save", "Ctrl+S") ?: "Ctrl+S",
        filesShortcut = preferences.getString("shortcut_files", "Ctrl+P") ?: "Ctrl+P",
        terminalShortcut = preferences.getString("shortcut_terminal", "Ctrl+`") ?: "Ctrl+`",
        sidebarShortcut = preferences.getString("shortcut_sidebar", "Ctrl+B") ?: "Ctrl+B",
        restoreTerminal = preferences.getBoolean("restore_terminal", true),
        restoreSession = preferences.getBoolean("restore_session", true),
    )

    fun save(value: IdePreferences) {
        preferences.edit {
            putFloat("dex_ui_scale", value.dexUiScale)
            putString("theme", value.theme.name)
            putFloat("editor_font_sp", value.editorFontSizeSp)
            putFloat("terminal_font_sp", value.terminalFontSizeSp)
            putInt("tab_width", value.tabWidth)
            putBoolean("indent_spaces", value.indentWithSpaces)
            putBoolean("word_wrap", value.wordWrap)
            putLong("autosave_delay", value.autosaveDelayMs)
            putInt("build_jobs", value.buildJobs)
            putBoolean("thermal_aware", value.thermalAware)
            putString("shortcut_run", value.runShortcut)
            putString("shortcut_save", value.saveShortcut)
            putString("shortcut_files", value.filesShortcut)
            putString("shortcut_terminal", value.terminalShortcut)
            putString("shortcut_sidebar", value.sidebarShortcut)
            putBoolean("restore_terminal", value.restoreTerminal)
            putBoolean("restore_session", value.restoreSession)
        }
    }
}

@Composable
internal fun IdeSettingsDialog(
    preferences: IdePreferences,
    dexWorkspace: Boolean,
    onChange: (IdePreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 470.dp)
                .background(Panel)
                .border(1.dp, Border)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "×",
                    color = Muted,
                    fontSize = 24.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            Text("APPEARANCE", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            Text("Theme", color = Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Applied to the workspace, editor, panels and terminal.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdeTheme.entries.forEach { theme ->
                    ChoiceChip(
                        label = theme.label,
                        selected = preferences.theme == theme,
                        onClick = { onChange(preferences.copy(theme = theme)) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("DeX interface size", color = Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (dexWorkspace) "Changes the complete desktop workspace, including the editor."
                else "Available when FoldCode is running in a DeX-sized window. Phone sizing stays unchanged.",
                color = Muted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f to "100%", 1.25f to "125%", 1.5f to "150%", 1.75f to "175%").forEach { (scale, label) ->
                    ChoiceChip(
                        label = label,
                        selected = preferences.dexUiScale == scale,
                        enabled = dexWorkspace,
                        onClick = { onChange(preferences.copy(dexUiScale = scale)) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            SectionTitle("EDITOR")
            Text("Editor font size", color = Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            ChoiceRow(listOf(0f to "Auto", 12f to "12", 14f to "14", 16f to "16", 18f to "18"), preferences.editorFontSizeSp) {
                onChange(preferences.copy(editorFontSizeSp = it))
            }
            Spacer(Modifier.height(12.dp))
            Text("Terminal font size", color = Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            ChoiceRow(listOf(0f to "Auto", 11f to "11", 12f to "12", 14f to "14", 16f to "16"), preferences.terminalFontSizeSp) {
                onChange(preferences.copy(terminalFontSizeSp = it))
            }
            Spacer(Modifier.height(12.dp))
            Text("Tab width", color = Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 8).forEach { width ->
                    ChoiceChip(width.toString(), preferences.tabWidth == width) { onChange(preferences.copy(tabWidth = width)) }
                }
            }
            SettingToggle("Insert spaces when pressing Tab", preferences.indentWithSpaces) {
                onChange(preferences.copy(indentWithSpaces = it))
            }
            SettingToggle("Word wrap", preferences.wordWrap) { onChange(preferences.copy(wordWrap = it)) }

            SectionTitle("FILES")
            Text("Autosave delay", color = Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L to "Off", 300L to "0.3 s", 700L to "0.7 s", 1_500L to "1.5 s", 3_000L to "3 s").forEach { (delay, label) ->
                    ChoiceChip(label, preferences.autosaveDelayMs == delay) { onChange(preferences.copy(autosaveDelayMs = delay)) }
                }
            }

            SectionTitle("BUILD")
            Text("Parallel jobs", color = Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Auto", 1 to "1", 2 to "2", 4 to "4", 6 to "6").forEach { (jobs, label) ->
                    ChoiceChip(label, preferences.buildJobs == jobs) { onChange(preferences.copy(buildJobs = jobs)) }
                }
            }
            SettingToggle("Reduce workers during high thermal pressure", preferences.thermalAware) {
                onChange(preferences.copy(thermalAware = it))
            }

            SectionTitle("DESKTOP SHORTCUTS")
            Text("Use forms such as F5, Ctrl+S, Ctrl+Shift+F or Ctrl+`.", color = Muted, fontSize = 10.sp)
            ShortcutField("Run", preferences.runShortcut) { onChange(preferences.copy(runShortcut = it)) }
            ShortcutField("Save", preferences.saveShortcut) { onChange(preferences.copy(saveShortcut = it)) }
            ShortcutField("Files", preferences.filesShortcut) { onChange(preferences.copy(filesShortcut = it)) }
            ShortcutField("Terminal", preferences.terminalShortcut) { onChange(preferences.copy(terminalShortcut = it)) }
            ShortcutField("Sidebar", preferences.sidebarShortcut) { onChange(preferences.copy(sidebarShortcut = it)) }

            SectionTitle("STARTUP")
            SettingToggle("Restore open files and active tab", preferences.restoreSession) {
                onChange(preferences.copy(restoreSession = it))
            }
            SettingToggle("Restore terminal panel visibility", preferences.restoreTerminal) {
                onChange(preferences.copy(restoreTerminal = it))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Spacer(Modifier.height(22.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    Spacer(Modifier.height(14.dp))
    Text(title, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ChoiceRow(values: List<Pair<Float, String>>, selected: Float, onSelect: (Float) -> Unit) {
    Spacer(Modifier.height(7.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { (value, label) -> ChoiceChip(label, selected == value) { onSelect(value) } }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, color = Foreground, fontSize = 11.sp)
    }
}

@Composable
private fun ShortcutField(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Foreground, fontSize = 11.sp, modifier = Modifier.weight(1f))
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(24)) },
            singleLine = true,
            textStyle = TextStyle(color = Foreground, fontSize = 11.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.widthIn(min = 130.dp, max = 170.dp).background(EditorTab).border(1.dp, Border).padding(8.dp),
        )
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> Accent
        else -> EditorTab
    }
    val content = when {
        !enabled -> Muted.copy(alpha = 0.45f)
        selected -> Color.White
        else -> Foreground
    }
    Box(
        Modifier
            .background(background.copy(alpha = if (enabled) 1f else 0.45f))
            .border(1.dp, if (selected) Accent else Border)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = 11.sp, maxLines = 1)
    }
}
