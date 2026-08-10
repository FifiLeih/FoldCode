package dev.foldcode.ide

/**
 * Unfolded and tablet widths use a persistent activity rail without desktop chrome.
 * Shared workspace content is rendered at the stable responsive call site.
 */
internal fun UnfoldedWorkspaceLayout(
    measurements: WorkspaceLayoutMeasurements,
) = WorkspaceLayoutVariant(
    desktopWorkspace = false,
    persistentSidebar = true,
    compactToolbar = false,
    workspaceDensity = measurements.baseDensity,
    uiScale = 1f,
    dexBrowserFile = null,
)
