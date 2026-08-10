package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NinjaSharedStorageRulesTest {
    @Test
    fun `adds durable timestamp step only to compiler rules`() {
        val rules = """
            rule C_COMPILER__app_Debug
              depfile = ${'$'}DEP_FILE
              command = clang -o ${'$'}out -c ${'$'}in
            rule CXX_EXECUTABLE_LINKER__app_Debug
              command = clang++ ${'$'}in -o ${'$'}out
            rule ASM_COMPILER__boot_Debug
              command = clang -o ${'$'}out -c ${'$'}in
        """.trimIndent() + "\n"

        val stabilized = stabilizeNinjaCompilerOutputTimestamps(rules)

        assertTrue(stabilized.contains("clang -o ${'$'}out -c ${'$'}in && /system/bin/touch -c \"${'$'}out\""))
        assertTrue(stabilized.contains("rule ASM_COMPILER__boot_Debug\n  command = clang -o ${'$'}out -c ${'$'}in && /system/bin/touch -c \"${'$'}out\""))
        assertTrue(stabilized.contains("command = clang++ ${'$'}in -o ${'$'}out\n"))
        assertFalse(stabilized.contains("clang++ ${'$'}in -o ${'$'}out && /system/bin/touch"))
    }

    @Test
    fun `stabilization is idempotent`() {
        val rules = "rule CXX_COMPILER__app_Release\n  command = clang++ -o ${'$'}out -c ${'$'}in\n"
        val once = stabilizeNinjaCompilerOutputTimestamps(rules)
        assertEquals(once, stabilizeNinjaCompilerOutputTimestamps(once))
    }

    @Test
    fun `installs stabilized rules once`() {
        val build = Files.createTempDirectory("foldcode-ninja-rules").toFile()
        try {
            val rules = File(build, "CMakeFiles/rules.ninja").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("rule C_COMPILER__app_Debug\n  command = clang -o ${'$'}out -c ${'$'}in\n")
            }

            assertTrue(installNinjaSharedStorageRules(build))
            assertTrue(rules.readText().contains("/system/bin/touch -c \"${'$'}out\""))
            assertFalse(installNinjaSharedStorageRules(build))
        } finally {
            build.deleteRecursively()
        }
    }
}
