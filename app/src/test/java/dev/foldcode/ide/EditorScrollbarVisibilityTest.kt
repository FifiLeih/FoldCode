package dev.foldcode.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorScrollbarVisibilityTest {
    @Test
    fun `horizontal bar appears as soon as a line exceeds its pane`() {
        assertFalse(hasHorizontalEditorOverflow(scrollMaxX = 50, viewportWidth = 100))
        assertTrue(hasHorizontalEditorOverflow(scrollMaxX = 51, viewportWidth = 100))
    }

    @Test
    fun `vertical bar ignores Sora trailing scroll space`() {
        assertFalse(
            hasVerticalEditorOverflow(
                scrollMaxY = 50,
                viewportHeight = 100,
                verticalExtraSpaceFactor = 0.5f,
            ),
        )
        assertTrue(
            hasVerticalEditorOverflow(
                scrollMaxY = 51,
                viewportHeight = 100,
                verticalExtraSpaceFactor = 0.5f,
            ),
        )
    }
}
