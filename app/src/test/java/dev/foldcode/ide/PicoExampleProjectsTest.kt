package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PicoExampleProjectsTest {
    @Test
    fun curatedExamplesSupportBothSdkSourceLanguages() {
        PicoExample.entries.forEach { example ->
            assertTrue(example.supports(PicoProjectLanguage.C))
            assertTrue(example.supports(PicoProjectLanguage.Cpp))
        }
    }

    @Test
    fun everyExampleAddsItsRequiredSdkTargetsToCmake() {
        val base = """target_link_libraries(${'$'}{FOLDCODE_TARGET} pico_stdlib)
pico_add_extra_outputs(${'$'}{FOLDCODE_TARGET})
"""
        PicoExample.entries.forEach { example ->
            val cmake = addExampleSdkLibraries(base, example.sdkLibraries)
            example.sdkLibraries.forEach { library ->
                assertTrue("${example.name} should link $library", library in cmake)
            }
        }
    }

    @Test
    fun addingSdkTargetsPreservesExistingWirelessArchitectureTarget() {
        val base = "target_link_libraries(${'$'}{FOLDCODE_TARGET} pico_stdlib pico_cyw43_arch_none)"

        val cmake = addExampleSdkLibraries(base, setOf("hardware_i2c", "hardware_irq"))

        assertEquals(
            "target_link_libraries(${'$'}{FOLDCODE_TARGET} pico_stdlib pico_cyw43_arch_none hardware_i2c hardware_irq)",
            cmake,
        )
    }

    @Test
    fun everyCatalogEntryGeneratesAnAttributedStandaloneSource() {
        PicoExample.entries.forEach { example ->
            val source = picoExampleSource(PicoBoard.Pico2, example)
            assertTrue("${example.name} should have a main function", "int main()" in source)
            assertTrue("${example.name} should retain upstream attribution", "BSD-3-Clause" in source)
        }
    }

    @Test
    fun everyBoardExampleAndSdkLanguageCombinationHasAUsableSource() {
        var combinations = 0
        PicoBoard.entries.forEach { board ->
            PicoExample.entries.forEach { example ->
                val source = picoExampleSource(board, example)
                listOf(PicoProjectLanguage.C, PicoProjectLanguage.Cpp).forEach { language ->
                    assertTrue("${board.name}/${example.name}/${language.name} must be supported", example.supports(language))
                    assertTrue("${board.name}/${example.name} should contain main", "int main()" in source)
                    assertTrue("${board.name}/${example.name} should retain its license", "BSD-3-Clause" in source)
                    combinations++
                }
            }
        }
        assertEquals(70, combinations)
    }

    @Test
    fun curatedSdkExamplesDoNotClaimMicroPythonOrRustSupport() {
        PicoExample.entries.forEach { example ->
            assertTrue(!example.supports(PicoProjectLanguage.MicroPython))
            assertTrue(!example.supports(PicoProjectLanguage.Rust))
        }
    }
}
