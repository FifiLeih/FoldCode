package dev.foldcode.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceOrientationPolicyTest {
    @Test
    fun `folded compact workspace is portrait locked`() {
        assertTrue(shouldLockFoldedWorkspaceToPortrait(599))
    }

    @Test
    fun `unfolded and Dex workspaces keep user orientation`() {
        assertFalse(shouldLockFoldedWorkspaceToPortrait(600))
        assertFalse(shouldLockFoldedWorkspaceToPortrait(1080))
    }

    @Test
    fun `unknown configuration does not force portrait`() {
        assertFalse(shouldLockFoldedWorkspaceToPortrait(0))
    }
}
