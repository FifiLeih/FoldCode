package dev.foldcode.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CIntelligenceRoutingTest {
    @Test
    fun standardCUsesCCompletionsWithoutCppOnlyKeywords() {
        val labels = cFamilyCompletionItems(
            source = "int main(void) { ret }",
            fileName = "main.c",
            picoProject = false,
        ).mapTo(mutableSetOf()) { it.label }

        assertTrue("return" in labels)
        assertTrue("restrict" in labels)
        assertFalse("namespace" in labels)
    }

    @Test
    fun picoCOffersSdkApiBeforeFirstConfiguration() {
        val labels = cFamilyCompletionItems(
            source = "#include \"pico/stdlib.h\"\nint main(void) { gpio_ }",
            fileName = "main.c",
            picoProject = true,
        ).mapTo(mutableSetOf()) { it.label }

        assertTrue("gpio_init" in labels)
        assertTrue("gpio_put" in labels)
        assertTrue("sleep_ms" in labels)
    }

    @Test
    fun cFilesAreRoutedCaseInsensitively() {
        assertTrue(isCppIntelligenceFile("src/main.c"))
        assertTrue(isCppIntelligenceFile("src/MAIN.C"))
    }
}
