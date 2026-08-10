package dev.foldcode.ide

import java.io.File
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnuLanguageIntelligenceTest {
    @Test
    fun closingCompilerOutputDuringDiagnosticCancellationIsNotFatal() {
        var waitedForExit = false

        val output = readGnuCompilerOutput(
            readOutput = { throw InterruptedIOException("read interrupted by close() on another thread") },
            waitForExit = { waitedForExit = true },
        )

        assertNull(output)
        assertFalse(waitedForExit)
    }

    @Test
    fun fortranIndexesModulesProceduresAndDerivedTypeFields() {
        val module = """
            module geometry
              type :: point
                real :: x, y
              end type point
            contains
              real function distance(a, b)
                real, intent(in) :: a, b
                distance = abs(a - b)
              end function distance
            end module geometry
        """.trimIndent()
        val main = """
            program demo
              use geo
              type(point) :: origin
              origin%
            end program demo
        """.trimIndent()
        val project = mapOf("geometry.f90" to module, "main.f90" to main)

        val useCursor = main.indexOf("geo") + 3
        val useItems = gnuLanguageCompletions("main.f90", main, useCursor, project)
        assertEquals("module · geometry.f90", useItems.first { it.label == "geometry" }.detail)
        assertTrue(useItems.any { it.label == "distance" && "function" in it.detail })

        val memberItems = gnuLanguageCompletions("main.f90", main, main.indexOf("origin%") + 7, project)
        assertTrue(memberItems.take(4).joinToString { "${it.label}:${it.detail}" }, memberItems.take(4).any { it.label == "x" })
        assertTrue(memberItems.take(4).joinToString { "${it.label}:${it.detail}" }, memberItems.take(4).any { it.label == "y" })
    }

    @Test
    fun cobolIndexesCopybookDataAndParagraphs() {
        val copybook = "01 CUSTOMER-NAME PIC X(40)."
        val main = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. CUSTOMER-DEMO.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            COPY CUSTOMER.
            PROCEDURE DIVISION.
                DISPLAY CUS
            SHOW-CUSTOMER.
                DISPLAY CUSTOMER-NAME.
        """.trimIndent()
        val project = mapOf("main.cob" to main, "CUSTOMER.cpy" to copybook)
        val items = gnuLanguageCompletions("main.cob", main, main.indexOf("DISPLAY CUS") + 11, project)

        assertTrue(items.take(5).any { it.label == "CUSTOMER-NAME" })
        assertTrue(items.any { it.label == "SHOW-CUSTOMER" && "paragraph" in it.detail })
    }

    @Test
    fun reportsUnclosedLanguageBlocksWithoutBuilding() {
        val fortran = """
            program broken
              if (.true.) then
                print *, "hello"
            end program broken
        """.trimIndent()
        val cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. BROKEN.
            PROCEDURE DIVISION.
                IF 1 = 1
                    DISPLAY "hello"
        """.trimIndent()

        assertTrue(gnuLanguageDiagnostics("main.f90", fortran).any { it.message == "Missing END IF" })
        assertTrue(gnuLanguageDiagnostics("main.cob", cobol).any { it.message == "Missing END-IF" })
    }

    @Test
    fun parsesGnuFortranAndCobolCompilerDiagnostics() {
        val output = """
            /tmp/project/main.f90:7:14:

                7 |   print *, missing_name
                  |              1
            Error: Symbol 'missing_name' at (1) has no IMPLICIT type
            /tmp/project/main.cob:9: error: syntax error, unexpected TO
        """.trimIndent()

        val diagnostics = parseDiagnostics(output)
        assertTrue(diagnostics.any {
            it.file == "main.f90" && it.line == 7 && it.column == 14 && it.severity == "error"
        })
        assertTrue(diagnostics.any {
            it.file == "main.cob" && it.line == 9 && it.severity == "error"
        })
    }

    @Test
    fun cobolLiveDiagnosticsUseTheSameFreeFormatAsBuilds() {
        val mirror = File("/tmp/foldcode-cobol")
        val source = File(mirror, "main.cob")

        val arguments = cobolCompilerDiagnosticArguments(mirror, source)

        assertTrue("-free" in arguments)
        assertTrue("-fsyntax-only" in arguments)
        assertEquals(source.absolutePath, arguments.last())
    }

    @Test
    fun retriesOnlyMissingGccInternalExecutableLaunches() {
        assertTrue(
            isTransientGnuInternalCompilerLaunchFailure(
                "gcc: fatal error: cannot execute '/usr/lib/gcc/aarch64-linux-gnu/12/cc1': " +
                    "execv: No such file or directory",
            ),
        )
        assertTrue(
            !isTransientGnuInternalCompilerLaunchFailure(
                "main.cob:10: error: syntax error, unexpected end of file",
            ),
        )
    }
}
