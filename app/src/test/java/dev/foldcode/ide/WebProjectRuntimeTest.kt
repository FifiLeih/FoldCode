package dev.foldcode.ide

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebProjectRuntimeTest {
    @Test
    fun webServerCommandKeepsUsefulOutputAndSuppressesNodeBootstrapNoise() {
        val command = WebProjectRuntime.PreparedProject(
            directory = File("/data/user/0/dev.foldcode.ide/files/web-projects/example"),
            installRequired = true,
            dependencyFingerprint = "fingerprint",
            nodeLauncher = File("/data/user/0/dev.foldcode.ide/files/terminal-bin/node"),
        ).startCommand()

        assertTrue(command.contains("NODE_NO_WARNINGS=1"))
        assertTrue(command.contains("Installing web dependencies"))
        assertTrue(command.contains("--loglevel=error --no-audit --no-fund"))
        assertTrue(command.contains("Starting web server"))
        assertTrue(command.contains("npm start --silent"))
    }

    @Test
    fun cachedDependenciesSkipInstallationMessage() {
        val command = WebProjectRuntime.PreparedProject(
            directory = File("/data/user/0/dev.foldcode.ide/files/web-projects/example"),
            installRequired = false,
            dependencyFingerprint = "fingerprint",
            nodeLauncher = File("/data/user/0/dev.foldcode.ide/files/terminal-bin/node"),
        ).startCommand()

        assertFalse(command.contains("Installing web dependencies"))
        assertTrue(command.contains("Starting web server"))
    }

    @Test
    fun readOnlyNpmQueriesDoNotRefreshOrRewriteTheRunningProject() {
        listOf("version", "--version", "-v", "ls", "fund", "view react version").forEach { arguments ->
            assertFalse(arguments, npmCommandRequiresSourceRefresh(arguments))
        }
        listOf("install react", "uninstall react", "version patch", "pkg set private=true", "audit fix")
            .forEach { arguments -> assertTrue(arguments, npmCommandRequiresSourceRefresh(arguments)) }

        val query = WebProjectRuntime.PreparedProject(
            directory = File("/data/user/0/dev.foldcode.ide/files/web-projects/example"),
            installRequired = false,
            dependencyFingerprint = "fingerprint",
            nodeLauncher = File("/data/user/0/dev.foldcode.ide/files/terminal-bin/node"),
        ).npmCommand("version", File("/storage/emulated/0/FoldCode/Projects/example"), syncProjectManifests = false)

        assertFalse(query.contains("foldcode_manifest"))
        assertFalse(query.contains("cp \"\$foldcode_manifest\""))
    }

    @Test
    fun webServerAndDependencyMutationCommandsAreClassifiedSeparately() {
        assertTrue(isWebServerCommand("npm start"))
        assertTrue(isWebServerCommand("npm run dev -- --host 127.0.0.1"))
        assertTrue(isWebServerCommand("npx vite"))
        assertFalse(isWebServerCommand("npm version"))

        assertTrue(isNpmProjectMutation("npm install react"))
        assertTrue(isNpmProjectMutation("npm version patch"))
        assertFalse(isNpmProjectMutation("npm version"))
        assertFalse(isNpmProjectMutation("npm ls"))
    }

    @Test
    fun viteTemplateUsesOneCounterLabelForInitialAndClickedStates() {
        val source = viteTypeScriptProject.getValue("src/main.ts")

        assertEquals(1, Regex("Count:").findAll(source).count())
        assertFalse(source.contains("Count: 0</button>"))
        assertTrue(source.contains("renderCount();"))
    }

    @Test
    fun everyWebTemplateHasAStartCommandAndResolvableBrowserEntryPoint() {
        listOf(
            webProject to "app.js",
            viteTypeScriptProject to "src/main.ts",
            reactTypeScriptProject to "src/main.tsx",
        ).forEach { (project, entryPoint) ->
            val packageJson = project.getValue("package.json")
            val html = project.getValue("index.html")
            assertTrue(packageJson.contains("\"start\""))
            assertTrue("$entryPoint must exist", project.containsKey(entryPoint))
            assertTrue("index.html must reference $entryPoint", entryPoint in html)
            assertTrue(project.keys.all(::isEditableProjectTextFile))
        }
    }

    @Test
    fun intelligenceUsesPrivateMirrorOnlyAfterDependenciesAreCurrent() {
        val source = Files.createTempDirectory("foldcode-web-source").toFile()
        val runtime = Files.createTempDirectory("foldcode-web-runtime").toFile()
        try {
            assertEquals(source, selectWebIntelligenceDirectory(source, runtime, "current"))

            File(runtime, "node_modules/react").mkdirs()
            File(runtime, ".foldcode-dependencies").writeText("stale")
            assertEquals(source, selectWebIntelligenceDirectory(source, runtime, "current"))

            File(runtime, ".foldcode-dependencies").writeText("current")
            assertEquals(runtime, selectWebIntelligenceDirectory(source, runtime, "current"))
        } finally {
            source.deleteRecursively()
            runtime.deleteRecursively()
        }
    }
}
