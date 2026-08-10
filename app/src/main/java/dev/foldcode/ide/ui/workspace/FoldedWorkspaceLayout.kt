package dev.foldcode.ide

/**
 * Folded phones keep the top bar and place navigation in an overlay drawer.
 * Shared workspace content is rendered at the stable responsive call site.
 */
internal fun FoldedWorkspaceLayout(
    measurements: WorkspaceLayoutMeasurements,
) = WorkspaceLayoutVariant(
    desktopWorkspace = false,
    persistentSidebar = false,
    compactToolbar = true,
    workspaceDensity = measurements.baseDensity,
    uiScale = 1f,
    dexBrowserFile = null,
)
