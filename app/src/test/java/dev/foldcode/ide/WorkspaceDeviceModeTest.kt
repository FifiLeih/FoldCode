package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceDeviceModeTest {
    @Test
    fun foldedModeUsesOverlayNavigationBelowPersistentRailBreakpoint() {
        assertEquals(WorkspaceDeviceMode.Folded, workspaceDeviceMode(599f, 900f))
    }

    @Test
    fun unfoldedModeKeepsPersistentRailWithoutDesktopHeight() {
        assertEquals(WorkspaceDeviceMode.Unfolded, workspaceDeviceMode(839f, 900f))
        assertEquals(WorkspaceDeviceMode.Unfolded, workspaceDeviceMode(900f, 479f))
    }

    @Test
    fun dexRequiresBothDesktopWidthAndHeight() {
        assertEquals(WorkspaceDeviceMode.Dex, workspaceDeviceMode(840f, 480f))
    }
}
