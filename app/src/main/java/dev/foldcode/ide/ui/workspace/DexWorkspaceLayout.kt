package dev.foldcode.ide

/**
 * DeX adds desktop chrome, scalable density, split editors, and the auxiliary browser.
 * Shared workspace content is rendered at the stable responsive call site.
 */
internal fun DexWorkspaceLayout(
    measurements: WorkspaceLayoutMeasurements,
) = WorkspaceLayoutVariant(
    desktopWorkspace = true,
    persistentSidebar = true,
    compactToolbar = false,
    workspaceDensity = measurements.dexDensity,
    uiScale = measurements.dexScale,
    dexBrowserFile = measurements.dexBrowserFile,
)
