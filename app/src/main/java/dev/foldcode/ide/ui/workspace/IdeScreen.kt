package dev.foldcode.ide

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

@Composable
internal fun IdeScreen() {
    val context = LocalContext.current
    var storageAccess by remember { mutableStateOf(hasProjectStorageAccess(context)) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        storageAccess = hasProjectStorageAccess(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageAccess = granted || hasProjectStorageAccess(context)
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            settingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:${context.packageName}".toUri(),
                ),
            )
        } else {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(Unit) {
        if (!storageAccess) requestStorageAccess()
    }

    if (storageAccess) {
        IdeWorkspace()
    } else {
        Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                Text("Projects storage access", color = Foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "FoldCode stores projects in Internal storage/FoldCode/Projects, beside Pictures and Movies.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = ::requestStorageAccess) { Text("Allow storage access") }
            }
        }
    }
}

private fun hasProjectStorageAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
} else {
    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
