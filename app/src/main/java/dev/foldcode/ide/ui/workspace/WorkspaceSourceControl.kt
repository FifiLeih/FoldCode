package dev.foldcode.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import androidx.compose.ui.window.Dialog

private data class SearchMatch(
    val fileName: String,
    val lineNumber: Int,
    val preview: String,
    val column: Int,
)

@Composable
internal fun SearchPanel(files: List<ProjectFile>, onNavigateToFile: (String, Int, Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(files, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            files.flatMap { file ->
                file.content.lineSequence().mapIndexedNotNull { index, line ->
                    val column = line.indexOf(query, ignoreCase = true)
                    if (column >= 0) {
                        SearchMatch(file.name, index + 1, line.trim().ifEmpty { "(blank line)" }, column + 1)
                    } else null
                }.toList()
            }.take(250)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Foreground, fontSize = 13.sp),
            cursorBrush = SolidColor(Accent),
            decorationBox = { field ->
                Row(
                    Modifier.fillMaxWidth().height(38.dp).background(Background).border(1.dp, Border).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⌕", color = Muted, fontSize = 17.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search all files", color = Muted, fontSize = 12.sp)
                        field()
                    }
                    if (query.isNotEmpty()) {
                        Text("×", color = Muted, fontSize = 17.sp, modifier = Modifier.clickable { query = "" })
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                query.isBlank() -> "Enter text to search the project"
                matches.isEmpty() -> "No results"
                else -> "${matches.size} result${if (matches.size == 1) "" else "s"}"
            },
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            matches.forEach { match ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        onNavigateToFile(match.fileName, match.lineNumber, match.column)
                    }.padding(horizontal = 4.dp, vertical = 7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fileGlyph(match.fileName), color = fileColor(match.fileName), fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(match.fileName, color = Foreground, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Text(":${match.lineNumber}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    Text(
                        match.preview,
                        color = Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp, top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SourceControlPanel(
    projectName: String,
    state: GitRepositoryState,
    busy: Boolean,
    notice: String?,
    username: String,
    token: String,
    onOpenFile: (String) -> Unit,
    onInitialize: () -> Unit,
    onCommit: (String) -> Unit,
    onRefresh: () -> Unit,
    onCredentialsChange: (String, String) -> Unit,
    onPull: (String, String) -> Unit,
    onPush: (String, String) -> Unit,
    onPublish: (String, Boolean, String, String) -> Unit,
) {
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var showPublishDialog by rememberSaveable { mutableStateOf(false) }
    var showCredentialsDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice == "Commit created") commitMessage = ""
    }
    LaunchedEffect(state.remoteUrl) {
        if (state.remoteUrl != null) showPublishDialog = false
    }

    if (showPublishDialog) {
        PublishBranchDialog(
            suggestedName = projectName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-'),
            username = username,
            token = token,
            busy = busy,
            error = notice,
            onCredentialsChange = onCredentialsChange,
            onDismiss = { if (!busy) showPublishDialog = false },
            onPublish = onPublish,
        )
    }
    if (showCredentialsDialog) {
        GitHubCredentialsDialog(
            username = username,
            token = token,
            onCredentialsChange = onCredentialsChange,
            onDismiss = { showCredentialsDialog = false },
            onContinue = { enteredUsername, enteredToken ->
                showCredentialsDialog = false
                onPush(enteredUsername, enteredToken)
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (!state.initialized) {
            Button(
                onClick = onInitialize,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(40.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Text(if (busy) "Initializing…" else "Initialize Repository", fontSize = 12.sp)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("⌄  CHANGES", color = Foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${state.changes.size}", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.width(9.dp))
                Text("↻", color = if (busy) Muted else Foreground, fontSize = 18.sp, modifier = Modifier.clickable(enabled = !busy, onClick = onRefresh))
            }
            Spacer(Modifier.height(10.dp))
            GitTextField(commitMessage, { commitMessage = it }, "Message to commit on '${state.branch}'")
            Spacer(Modifier.height(7.dp))
            if (state.changes.isNotEmpty()) {
                Button(
                    onClick = { onCommit(commitMessage) },
                    enabled = !busy && commitMessage.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text(if (busy) "Working…" else "✓  Commit", fontSize = 12.sp) }
            } else if (state.remoteUrl == null) {
                Button(
                    onClick = { showPublishDialog = true },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("☁  Publish Branch", fontSize = 12.sp) }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(
                        onClick = { onPull(username, token) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Border, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("↓ Pull", fontSize = 10.sp) }
                    Button(
                        onClick = { if (token.isBlank()) showCredentialsDialog = true else onPush(username, token) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("↑ Push", fontSize = 10.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (state.changes.isNotEmpty()) {
                Text("Changes", color = Foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                state.changes.forEach { change -> GitChangeRow(change, onOpenFile) }
            } else {
                Text("✓ Working tree clean", color = Success, fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                Text("GRAPH", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text("◉  ${state.branch}", color = Foreground, fontSize = 11.sp)
            }
        }
        if (!notice.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            val success = notice in setOf("Commit created", "Pull completed", "Push completed", "Branch published to GitHub")
            Text(notice, color = if (success) Success else Color(0xFFFFB4AB), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun ExplorerPathDialog(
    action: ExplorerAction,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val title = when (action.type) {
        ExplorerActionType.CreateProject -> "New Project"
        ExplorerActionType.CreateFile -> "New File"
        ExplorerActionType.CreateFolder -> "New Folder"
        ExplorerActionType.Rename -> "Rename"
        ExplorerActionType.Move -> "Move to Folder"
    }
    val initialValue = when (action.type) {
        ExplorerActionType.Rename -> action.path.substringAfterLast('/')
        ExplorerActionType.Move -> action.path.substringBeforeLast('/', "")
        else -> ""
    }
    var value by remember(action) { mutableStateOf(initialValue) }
    GitDialog(title = title, onDismiss = onDismiss) {
        GitTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = when (action.type) {
                ExplorerActionType.CreateProject -> "Project name"
                ExplorerActionType.CreateFile -> "File name"
                ExplorerActionType.CreateFolder -> "Folder name"
                ExplorerActionType.Rename -> "New name"
                ExplorerActionType.Move -> "Destination folder (blank for project root)"
            },
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onConfirm(value) },
            enabled = action.type == ExplorerActionType.Move || value.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) { Text("Confirm", fontSize = 12.sp) }
    }
}

@Composable
internal fun NewProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, GeneralProjectTemplate) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(GeneralProjectTemplate.Cpp) }
    var category by remember { mutableStateOf(GeneralProjectTemplate.Cpp.category) }
    val categories = remember { GeneralProjectTemplate.entries.map { it.category }.distinct() }
    GitDialog(title = "New Project", onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            categories.forEach { option ->
                Text(
                    option,
                    color = if (category == option) Foreground else Muted,
                    fontWeight = if (category == option) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(if (category == option) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable {
                            category = option
                            template = GeneralProjectTemplate.entries.first { it.category == option }
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        GitTextField(value = name, onValueChange = { name = it }, placeholder = "Project name")
        Spacer(Modifier.height(14.dp))
        Text("Language / template", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        GeneralProjectTemplate.entries.filter { it.category == category }.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (template == option) Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { template = option }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (template == option) "●" else "○", color = Accent, fontSize = 12.sp)
                Spacer(Modifier.width(9.dp))
                Text(option.label, color = Foreground, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onConfirm(name, template) },
            enabled = name.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create") }
    }
}

@Composable
internal fun ProjectsFolderDialog(
    projects: List<GitRepositoryInfo>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    GitDialog(title = "Projects", onDismiss = onDismiss) {
        if (loading) {
            Text("Loading projects…", color = Muted, fontSize = 12.sp)
        } else if (projects.isEmpty()) {
            Text("No projects stored yet", color = Muted, fontSize = 12.sp)
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(projects, key = { it.projectKey }) { project ->
                    Row(
                        Modifier.fillMaxWidth().height(46.dp).clickable { onOpen(project.projectKey) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("▱", color = Accent, fontSize = 16.sp)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(project.name, color = Foreground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(project.branch, color = Muted, fontSize = 10.sp)
                        }
                        Text(
                            "×",
                            color = Color(0xFFFF8A80),
                            fontSize = 19.sp,
                            modifier = Modifier.clickable { onDelete(project.projectKey) }.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CloneRepositoryDialog(
    username: String,
    token: String,
    busy: Boolean,
    error: String?,
    onCredentialsChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onClone: (String, String, String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    GitDialog(title = "Clone Repository", onDismiss = onDismiss) {
        GitTextField(url, { url = it }, "https://github.com/owner/repository")
        Spacer(Modifier.height(8.dp))
        GitTextField(username, { onCredentialsChange(it, token) }, "GitHub username (private repositories)")
        Spacer(Modifier.height(8.dp))
        GitTextField(token, { onCredentialsChange(username, it) }, "Personal access token", password = true)
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = Color(0xFFFFB4AB), fontSize = 10.sp)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onClone(url, username, token) },
            enabled = !busy && url.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) { Text(if (busy) "Cloning…" else "Clone", fontSize = 12.sp) }
    }
}

@Composable
private fun PublishBranchDialog(
    suggestedName: String,
    username: String,
    token: String,
    busy: Boolean,
    error: String?,
    onCredentialsChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onPublish: (String, Boolean, String, String) -> Unit,
) {
    var repositoryName by rememberSaveable(suggestedName) { mutableStateOf(suggestedName) }
    var privateRepository by rememberSaveable { mutableStateOf(true) }
    GitDialog(title = "Publish Branch", onDismiss = onDismiss) {
        GitTextField(username, { onCredentialsChange(it, token) }, "GitHub username")
        Spacer(Modifier.height(8.dp))
        GitTextField(token, { onCredentialsChange(username, it) }, "Personal access token", password = true)
        Spacer(Modifier.height(8.dp))
        GitTextField(repositoryName, { repositoryName = it }, "Repository name")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = privateRepository, onCheckedChange = { privateRepository = it })
            Text("Private repository", color = Foreground, fontSize = 11.sp)
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = Color(0xFFFFB4AB), fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onPublish(repositoryName, privateRepository, username, token) },
            enabled = !busy && username.isNotBlank() && token.isNotBlank() && repositoryName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) { Text(if (busy) "Publishing…" else "Publish to GitHub", fontSize = 12.sp) }
    }
}

@Composable
private fun GitHubCredentialsDialog(
    username: String,
    token: String,
    onCredentialsChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onContinue: (String, String) -> Unit,
) {
    GitDialog(title = "GitHub Credentials", onDismiss = onDismiss) {
        GitTextField(username, { onCredentialsChange(it, token) }, "GitHub username")
        Spacer(Modifier.height(8.dp))
        GitTextField(token, { onCredentialsChange(username, it) }, "Personal access token", password = true)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onContinue(username, token) },
            enabled = username.isNotBlank() && token.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) { Text("Continue", fontSize = 12.sp) }
    }
}

@Composable
private fun GitDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Panel).border(1.dp, Border).padding(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("×", color = Muted, fontSize = 20.sp, modifier = Modifier.clickable(onClick = onDismiss))
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun GitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Foreground, fontSize = 11.sp),
        cursorBrush = SolidColor(Accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { field ->
            Box(
                Modifier.fillMaxWidth().height(36.dp).background(Background).border(1.dp, Border).padding(horizontal = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GitChangeRow(change: GitFileChange, onOpenFile: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).clickable { onOpenFile(change.path) }.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fileGlyph(change.path), color = fileColor(change.path), fontSize = 11.sp)
        Spacer(Modifier.width(7.dp))
        Text(change.path, color = Foreground, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            change.code,
            color = when (change.code) {
                "A", "U" -> Success
                "D", "!" -> Color(0xFFFF8A80)
                else -> Color(0xFFE2C08D)
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}
