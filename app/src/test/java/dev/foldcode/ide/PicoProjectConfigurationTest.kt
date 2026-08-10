package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PicoProjectConfigurationTest {
    @Test
    fun markerRoundTripsSelectedBuildType() {
        val marker = picoConfigurationMarker(
            PicoProjectConfiguration(
                board = PicoBoard.Pico2,
                target = "firmware",
                buildType = PicoBuildType.RelWithDebInfo,
            ),
        )

        assertTrue(marker.contains("\"buildType\":\"RelWithDebInfo\""))
        assertEquals(
            PicoBuildType.RelWithDebInfo,
            picoProjectConfiguration(mapOf(PICO_PROJECT_MARKER to marker)).buildType,
        )
    }

    @Test
    fun rustMarkerKeepsRuntimeWhenBuildTypeChanges() {
        val marker = picoConfigurationMarker(
            PicoProjectConfiguration(
                board = PicoBoard.Pico2,
                buildType = PicoBuildType.Debug,
            ),
            runtime = "rust",
        )

        val files = mapOf(PICO_PROJECT_MARKER to marker)
        assertTrue(isPicoRustProject(files))
        assertEquals(PicoBuildType.Debug, picoProjectConfiguration(files).buildType)
    }

    @Test
    fun existingMarkersDefaultToRelease() {
        val marker = """{"board":"pico","platform":"rp2040","version":7}"""

        assertEquals(
            PicoBuildType.Release,
            picoProjectConfiguration(mapOf(PICO_PROJECT_MARKER to marker)).buildType,
        )
    }

    @Test
    fun everyBoardRoundTripsThroughTheProjectMarker() {
        PicoBoard.entries.forEach { board ->
            val marker = picoConfigurationMarker(PicoProjectConfiguration(board = board))
            assertEquals(board, picoBoard(mapOf(PICO_PROJECT_MARKER to marker)))
        }
    }

    @Test
    fun regularPicoBlinkUsesTheBoardGpioLed() {
        val source = picoBlinkExampleSource(PicoBoard.Pico2)

        assertTrue(source.contains("PICO_DEFAULT_LED_PIN"))
        assertTrue(source.contains("gpio_put(LED_PIN"))
        assertTrue(!source.contains("cyw43_arch_init"))
    }

    @Test
    fun wirelessPicoBlinkUsesTheCyw43Led() {
        listOf(PicoBoard.PicoW, PicoBoard.Pico2W).forEach { board ->
            val source = picoBlinkExampleSource(board)

            assertTrue(source.contains("pico/cyw43_arch.h"))
            assertTrue(source.contains("cyw43_arch_init()"))
            assertTrue(source.contains("CYW43_WL_GPIO_LED_PIN"))
            assertTrue(!source.contains("PICO_DEFAULT_LED_PIN"))
        }
    }

    @Test
    fun wirelessBlinkUsesCyw43WithoutPullingInLwip() {
        assertEquals("pico_cyw43_arch_none", picoWirelessArchitectureTarget(PicoBoard.PicoW))
        assertEquals("pico_cyw43_arch_none", picoWirelessArchitectureTarget(PicoBoard.Pico2W))
        assertEquals(null, picoWirelessArchitectureTarget(PicoBoard.Pico2))
    }

    @Test
    fun existingWirelessBlinkAddsItsProjectDirectoryToHeaderSearch() {
        val cmake = """target_link_libraries(${'$'}{FOLDCODE_TARGET} pico_stdlib pico_cyw43_arch_lwip_poll)
pico_add_extra_outputs(${'$'}{FOLDCODE_TARGET})
"""

        val upgraded = upgradeWirelessBlinkCmake(cmake)

        assertTrue(upgraded.contains("pico_cyw43_arch_none"))
        assertTrue(
            upgraded.contains(
                "target_include_directories(${'$'}{FOLDCODE_TARGET} PRIVATE ${'$'}{CMAKE_CURRENT_LIST_DIR})",
            ),
        )
    }
}
