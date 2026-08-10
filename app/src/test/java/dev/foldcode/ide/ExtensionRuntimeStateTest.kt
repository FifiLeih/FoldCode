package dev.foldcode.ide

import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExtensionRuntimeStateTest {
    @Test
    fun installUpdateAndRemovalChangeRuntimeKey() {
        val version8 = extension("1.0.0+8")
        val version9 = extension("1.0.0+9")

        assertNotEquals(extensionRuntimeKey(null), extensionRuntimeKey(version8))
        assertNotEquals(extensionRuntimeKey(version8), extensionRuntimeKey(version9))
        assertNotEquals(extensionRuntimeKey(version9), extensionRuntimeKey(null))
    }

    private fun extension(version: String) = FoldCodeExtensionInfo(
        id = "dev.foldcode.web",
        name = "Web Development",
        version = version,
        description = "Web tools",
        publisher = "FoldCode",
        installedBytes = 1L,
        installedComponents = 1,
        totalComponents = 1,
        offlineReady = true,
        components = emptyList(),
        provides = setOf("command:npm"),
    )
}
