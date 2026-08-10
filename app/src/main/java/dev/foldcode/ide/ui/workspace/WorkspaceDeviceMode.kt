package dev.foldcode.ide

internal enum class WorkspaceDeviceMode {
    Folded,
    Unfolded,
    Dex,
}

/** Mirrors the original width/height breakpoints without depending on Compose. */
internal fun workspaceDeviceMode(widthDp: Float, layoutHeightDp: Float): WorkspaceDeviceMode = when {
    widthDp >= 840f && layoutHeightDp >= 480f -> WorkspaceDeviceMode.Dex
    widthDp >= 600f -> WorkspaceDeviceMode.Unfolded
    else -> WorkspaceDeviceMode.Folded
}
