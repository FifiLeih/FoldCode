package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedTargetIntelligenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun picoRiscvClangdDropsGnuOnlyArchitectureFlags() {
        val sanitized = sanitizePicoRiscvArgumentsForClangd(
            listOf(
                "/toolchain/bin/riscv32-unknown-elf-g++",
                "-march=rv32ima_zicsr_zifencei_zba_zbb_zbs_zbkb_zca_zcb_zcmp",
                "-mabi=ilp32",
                "-I/project/include",
                "-c",
                "/project/main.cpp",
            ),
        )

        assertEquals("/toolchain/bin/riscv32-unknown-elf-g++", sanitized.first())
        assertTrue("--target=riscv32-unknown-elf" in sanitized)
        assertTrue("-I/project/include" in sanitized)
        assertFalse(sanitized.any { it.startsWith("-march") })
        assertFalse(sanitized.any { it.startsWith("-mabi") })
    }

    @Test
    fun ordinaryArmCompileCommandIsNotRewritten() {
        val command = listOf("clang++", "--target=arm-none-eabi", "-mcpu=cortex-m33")

        assertEquals(command, sanitizePicoRiscvArgumentsForClangd(command))
    }

    @Test
    fun rustPicoTargetIsRecognizedAsEmbedded() {
        val project = temporaryFolder.newFolder("rust-pico")
        val cargoDirectory = project.resolve(".cargo").apply { mkdirs() }
        cargoDirectory.resolve("config.toml").writeText(
            """
            [build]
            target = "riscv32imac-unknown-none-elf"
            """.trimIndent(),
        )

        val target = rustAnalyzerCargoTarget(project)

        assertEquals("riscv32imac-unknown-none-elf", target)
        assertTrue(isEmbeddedRustTarget(target))
    }

    @Test
    fun ordinaryRustProjectHasNoEmbeddedTarget() {
        val project = temporaryFolder.newFolder("ordinary-rust")

        val target = rustAnalyzerCargoTarget(project)

        assertEquals(null, target)
        assertFalse(isEmbeddedRustTarget(target))
    }

    @Test
    fun picoRustUsesCargoFlycheckForEmbeddedMacroBodies() {
        val project = temporaryFolder.newFolder("rust-pico-settings")
        project.resolve(".cargo").apply { mkdirs() }.resolve("config.toml").writeText(
            """
            [build]
            target = "thumbv8m.main-none-eabihf"
            """.trimIndent(),
        )

        val settings = rustAnalyzerSettings(project)

        assertTrue(settings.getJSONObject("cargo").getJSONObject("buildScripts").getBoolean("enable"))
        assertTrue(settings.getJSONObject("procMacro").getBoolean("enable"))
        assertTrue(settings.getJSONObject("check").getBoolean("enable"))
        assertEquals("check", settings.getJSONObject("check").getString("command"))
        assertFalse(settings.getJSONObject("check").getBoolean("allTargets"))
    }

    @Test
    fun picoMarkerEnablesEmbeddedDiagnosticsWithoutResolvedCargoTarget() {
        val project = temporaryFolder.newFolder("rust-pico-marker-settings")
        project.resolve("Cargo.toml").writeText(
            """
            [package]
            name = "rust-pico-marker-settings"
            version = "0.1.0"
            edition = "2021"
            """.trimIndent(),
        )
        project.resolve("foldcode-pico.json").writeText("{}")

        assertTrue(isPicoRustProjectDirectory(project))
        assertEquals(null, rustAnalyzerCargoTarget(project))

        val settings = rustAnalyzerSettings(project)

        assertTrue(settings.getJSONObject("cargo").getJSONObject("buildScripts").getBoolean("enable"))
        assertTrue(settings.getJSONObject("procMacro").getBoolean("enable"))
        assertTrue(settings.getJSONObject("check").getBoolean("enable"))
    }

    @Test
    fun picoMarkerWithoutCargoManifestIsNotTreatedAsRustProject() {
        val project = temporaryFolder.newFolder("non-rust-pico-marker")
        project.resolve("foldcode-pico.json").writeText("{}")

        assertFalse(isPicoRustProjectDirectory(project))
    }

    @Test
    fun picoRustCargoFallbackDoesNotDependOnResolvedTarget() {
        val project = temporaryFolder.newFolder("rust-pico-fallback")
        project.resolve("Cargo.toml").writeText(
            """
            [package]
            name = "rust-pico-fallback"
            version = "0.1.0"
            edition = "2021"
            """.trimIndent(),
        )
        project.resolve("foldcode-pico.json").writeText("{}")
        project.resolve("src").mkdirs()

        assertTrue(requiresEmbeddedCargoDiagnostics(project))
        assertTrue(rustDocumentUri(project, "src/main.rs")?.endsWith("/src/main.rs") == true)
        assertEquals(null, rustDocumentUri(project, "../outside.rs"))
    }

    @Test
    fun rustDiagnosticSnapshotOverlaysUnsavedBufferWithoutChangingProject() {
        val project = temporaryFolder.newFolder("rust-pico-snapshot")
        project.resolve("Cargo.toml").writeText("[package]\nname = \"snapshot\"\nversion = \"0.1.0\"\n")
        project.resolve("foldcode-pico.json").writeText("{}")
        val source = project.resolve("src/main.rs").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("fn main() {}\n")
        }
        project.resolve(".foldcode/cargo/cache").mkdirs()
        val snapshot = temporaryFolder.newFolder("diagnostic-snapshot")

        val mirroredSource = prepareRustDiagnosticSnapshot(
            project,
            snapshot,
            "src/main.rs",
            "fn main() { unknown_name; }\n",
        )

        assertEquals("fn main() { unknown_name; }\n", mirroredSource?.readText())
        assertEquals("fn main() {}\n", source.readText())
        assertTrue(snapshot.resolve("Cargo.toml").isFile)
        assertFalse(snapshot.resolve(".foldcode").exists())
    }

    @Test
    fun legacyCargoConfigAlsoProvidesEmbeddedTarget() {
        val project = temporaryFolder.newFolder("rust-pico-legacy-config")
        project.resolve(".cargo").apply { mkdirs() }.resolve("config").writeText(
            """
            [build]
            target = "thumbv6m-none-eabi"
            """.trimIndent(),
        )

        assertEquals("thumbv6m-none-eabi", rustAnalyzerCargoTarget(project))
    }

    @Test
    fun terminalPromptIncludesCurrentProjectSubdirectory() {
        val project = temporaryFolder.newFolder("PICO_EXAMPLE")
        val build = project.resolve("build").apply { mkdirs() }

        assertEquals("PICO_EXAMPLE", terminalPrompt(project, project))
        assertEquals("PICO_EXAMPLE/build", terminalPrompt(project, build))
    }

    @Test
    fun rustAnalyzerConfigurationProjectsRequestedSection() {
        val settings = JSONObject()
            .put("cargo", JSONObject().put("target", "thumbv8m.main-none-eabihf"))
            .put("check", JSONObject().put("enable", false))

        val cargo = rustAnalyzerConfigurationValue(settings, "rust-analyzer.cargo") as JSONObject

        assertEquals("thumbv8m.main-none-eabihf", cargo.getString("target"))
        assertEquals(JSONObject.NULL, rustAnalyzerConfigurationValue(settings, "rust-analyzer.unknown"))
    }
}
