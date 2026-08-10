package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreProjectModelTest {
    @Test
    fun sourceTreeClassificationKeepsTextEditableAndBinaryArtifactsReadOnly() {
        listOf(
            "main.cpp",
            "src/main.rs",
            "web/App.tsx",
            "CMakeLists.txt",
            ".cargo/config",
            "build/compile_commands.json",
        ).forEach { path ->
            assertTrue("$path should be editable", isEditableProjectTextFile(path))
        }

        listOf("firmware.elf", "firmware.uf2", "library.a", "build/.ninja_deps").forEach { path ->
            assertFalse("$path must not be opened as text", isEditableProjectTextFile(path))
            assertTrue("$path should remain inspectable", isInspectableArtifact(path))
        }
    }

    @Test
    fun projectPathsAreNormalizedAndCannotEscapeTheProject() {
        assertEquals("src/include/header.hpp", validatedProjectPath(" /src\\include/header.hpp/ "))
        assertEquals(listOf("src", "src/include"), parentFolderPaths("src/include/header.hpp"))

        listOf("", "../secret", "src/../../secret", ".hidden", "src/bad:name").forEach { path ->
            assertThrows("$path must be rejected", IllegalArgumentException::class.java) {
                validatedProjectPath(path)
            }
        }
    }

    @Test
    fun projectDetectionSeparatesSdkMicroPythonRustAndOrdinaryCargoProjects() {
        val desktopPico = mapOf(
            "CMakeLists.txt" to """
                set(PICO_BOARD pico2_w)
                include(pico_sdk_import.cmake)
                project(example C CXX ASM)
                pico_sdk_init()
                add_executable(example main.c)
                pico_add_extra_outputs(example)
            """.trimIndent(),
            "main.c" to "int main(void) { return 0; }",
        )
        assertTrue(isPicoProject(desktopPico))
        assertTrue(isPicoSdkProject(desktopPico))
        assertEquals(PicoBoard.Pico2W, picoBoard(desktopPico))

        val microPython = mapOf(PICO_PROJECT_MARKER to """{"runtime":"micropython","board":"pico"}""")
        assertTrue(isPicoProject(microPython))
        assertTrue(isPicoMicroPythonProject(microPython))
        assertFalse(isPicoSdkProject(microPython))

        val picoRust = mapOf(
            PICO_PROJECT_MARKER to """{"runtime":"rust","board":"pico2","platform":"rp2350"}""",
            "Cargo.toml" to "[package]",
            "src/main.rs" to "fn main() {}",
        )
        assertTrue(isPicoRustProject(picoRust))
        assertTrue(isRustCargoProject(picoRust))
        assertFalse(isPicoSdkProject(picoRust))

        val ordinaryRust = mapOf("Cargo.toml" to "[package]", "src/lib.rs" to "pub fn library() {}")
        assertTrue(isRustCargoProject(ordinaryRust))
        assertFalse(isPicoProject(ordinaryRust))
    }

    @Test
    fun advancedPicoCmakeAnalysisFindsTargetsAndFullBuildFeatures() {
        val analysis = analyzePicoCMake(
            """
                add_executable(boot boot.c)
                add_executable(application app.c)
                target_compile_definitions(application PRIVATE FEATURE=1)
                pico_sign_binary(application key.pem)
                add_custom_command(OUTPUT generated.c COMMAND generator)
            """.trimIndent(),
        )

        assertEquals(listOf("boot", "application"), analysis.targets)
        assertTrue(analysis.requiresFullCMake)
        assertTrue(analysis.reasons.any { "multiple executable targets" in it })
        assertTrue(analysis.reasons.contains("binary signing"))
        assertTrue(analysis.reasons.contains("generated build output"))
    }
}
