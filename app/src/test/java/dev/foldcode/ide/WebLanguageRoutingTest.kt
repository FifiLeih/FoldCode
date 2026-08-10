package dev.foldcode.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLanguageRoutingTest {
    @Test
    fun webDocumentsNeverFallThroughToCppIntelligence() {
        listOf(
            "index.html",
            "src/style.css",
            "package.json",
            "src/App.tsx",
            "src/main.ts",
            "src/component.jsx",
        ).forEach { file ->
            assertTrue("Expected a web document: $file", isWebDocument(file))
            assertFalse("Web file reached C/C++ intelligence: $file", isCppIntelligenceFile(file))
        }
    }

    @Test
    fun everyWebDocumentHasADedicatedLanguageServer() {
        mapOf(
            "src/App.tsx" to "typescript",
            "src/main.ts" to "typescript",
            "src/view.jsx" to "typescript",
            "src/main.js" to "typescript",
            "index.html" to "html",
            "src/style.css" to "css",
            "package.json" to "json",
        ).forEach { (file, server) ->
            assertTrue(file, isWebIntelligenceFile(file))
            assertTrue("$file should use $server", webLanguageServerId(file) == server)
        }
    }

    @Test
    fun cppFilesStillUseCppIntelligence() {
        listOf("main.c", "main.cpp", "include/widget.hpp")
            .forEach { assertTrue(it, isCppIntelligenceFile(it)) }
    }

    @Test
    fun lspSnippetsBecomePlainEditorInsertions() {
        assertEquals("display: block;", plainLspInsertion("display: ${'$'}{1:block};${'$'}0"))
        assertEquals("console.log(message)", plainLspInsertion("console.log(${'$'}{1:message})${'$'}0"))
    }

    @Test
    fun declaredPackagesAreNotErrorsWhileNpmInstallationIsPending() {
        val dependencies = setOf("react", "react-dom", "@vitejs/plugin-react")

        assertTrue(isPendingWebDependencyDiagnostic(
            "Cannot find module 'react-dom/client' or its corresponding type declarations.",
            dependencies,
        ))
        assertTrue(isPendingWebDependencyDiagnostic(
            "This JSX tag requires the module path 'react/jsx-runtime' to exist, but none could be found.",
            dependencies,
        ))
        assertTrue(isPendingWebDependencyDiagnostic(
            "Cannot find module '@vitejs/plugin-react' or its corresponding type declarations.",
            dependencies,
        ))
        assertFalse(isPendingWebDependencyDiagnostic(
            "Cannot find module 'reactt' or its corresponding type declarations.",
            dependencies,
        ))
        assertFalse(isPendingWebDependencyDiagnostic(
            "Cannot find module './MissingComponent' or its corresponding type declarations.",
            dependencies,
        ))
    }
}
