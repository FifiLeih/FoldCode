package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicoExtensionInstallPolicyTest {
    @Test
    fun localInstallActivatesEveryComponentContainedInPackage() {
        val info = FoldCodeExtensionInfo(
            id = "dev.foldcode.pico",
            name = "Raspberry Pi Pico",
            version = "2.3.0+18",
            description = "Pico tools",
            publisher = "FoldCode",
            installedBytes = 0L,
            installedComponents = 0,
            totalComponents = 3,
            offlineReady = false,
            components = listOf(
                component("core", availableOffline = true),
                component("rp2040", availableOffline = true),
                component("rp2350", availableOffline = true),
                component("rp2350-riscv", availableOffline = false),
            ),
            provides = emptySet(),
        )

        assertEquals(
            linkedSetOf("core", "rp2040", "rp2350"),
            bundledPicoComponentIds(info),
        )
    }

    @Test
    fun deploymentUsesTheAggregateMicroPythonComponentReportedByThePanel() {
        val installed = extensionInfo(component("micropython", availableOffline = true, installed = true))
        val missing = extensionInfo(component("micropython", availableOffline = true, installed = false))

        assertTrue(microPythonFirmwareReady(installed, firmwareAvailable = true))
        assertFalse(microPythonFirmwareReady(installed, firmwareAvailable = false))
        assertFalse(microPythonFirmwareReady(missing, firmwareAvailable = true))
    }

    private fun extensionInfo(vararg components: FoldCodeExtensionComponent) = FoldCodeExtensionInfo(
        id = "dev.foldcode.pico",
        name = "Raspberry Pi Pico",
        version = "2.3.0+18",
        description = "Pico tools",
        publisher = "FoldCode",
        installedBytes = 0L,
        installedComponents = components.count(FoldCodeExtensionComponent::installed),
        totalComponents = components.size,
        offlineReady = components.all(FoldCodeExtensionComponent::installed),
        components = components.toList(),
        provides = emptySet(),
    )

    private fun component(
        id: String,
        availableOffline: Boolean,
        installed: Boolean = false,
    ) = FoldCodeExtensionComponent(
        id = id,
        name = id,
        installed = installed,
        availableOffline = availableOffline,
    )
}
