package dev.foldcode.ide

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

/** Android entry point. The IDE workspace lives in the UI layer. */
class MainActivity : ComponentActivity() {
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (DesktopPointerFocusRouter.routeScrollToTarget(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWorkspaceOrientation(resources.configuration)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                IdeScreen()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWorkspaceOrientation(newConfig)
    }

    private fun applyWorkspaceOrientation(configuration: Configuration) {
        val target = if (shouldLockFoldedWorkspaceToPortrait(configuration.smallestScreenWidthDp)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != target) requestedOrientation = target
    }
}

internal fun shouldLockFoldedWorkspaceToPortrait(smallestScreenWidthDp: Int): Boolean =
    smallestScreenWidthDp in 1 until 600
