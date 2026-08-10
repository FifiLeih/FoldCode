package dev.foldcode.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssemblyIntelligenceTest {
    @Test
    fun recognizesCommonAssemblyFileNames() {
        assertTrue(isAssemblySource("src/startup.S"))
        assertTrue(isAssemblySource("src/startup.s"))
        assertTrue(isAssemblySource("src/startup.asm"))
        assertFalse(isAssemblySource("src/startup.cpp"))
    }

    @Test
    fun completionTracksSelectedPicoArchitecture() {
        val arm = assemblyCompletionItems(
            mapOf("CMakeLists.txt" to "set(PICO_BOARD pico2)\nset(PICO_PLATFORM rp2350)"),
        )
        val riscV = assemblyCompletionItems(
            mapOf("CMakeLists.txt" to "set(PICO_BOARD pico2)\nset(PICO_PLATFORM rp2350-riscv)"),
        )

        assertTrue(arm.any { it.label == "r0" })
        assertFalse(arm.any { it.label == "a0" })
        assertTrue(riscV.any { it.label == "a0" })
        assertFalse(riscV.any { it.label == "r0" })
        assertTrue(riscV.any { it.label == "section" })
    }
}
