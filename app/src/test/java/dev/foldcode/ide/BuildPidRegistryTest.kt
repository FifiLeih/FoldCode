package dev.foldcode.ide

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildPidRegistryTest {
    @Test
    fun pidEventsTrackOnlyCurrentlyLiveGuestProcesses() {
        val events = """
            +201
            +202
            malformed
            -201
            +203
            -999
        """.trimIndent()

        assertEquals(
            setOf(100, 202, 203),
            parseBuildPidEvents(events, initial = setOf(100)),
        )
    }

    @Test
    fun laterRegistrationCanReplaceAnEarlierExitForSamePid() {
        assertEquals(setOf(301), parseBuildPidEvents("+301\n-301\n+301\n"))
    }

    @Test
    fun expandsEveryReachableBuildDescendantOnce() {
        val children = mapOf(
            10 to setOf(11, 12),
            11 to setOf(13),
            12 to setOf(13),
            13 to setOf(10),
        )

        assertEquals(
            linkedSetOf(10, 11, 12, 13),
            expandBuildProcessTree(setOf(10)) { children[it].orEmpty() },
        )
    }

    @Test
    fun nativeTrackingPreservesAnExistingPreload() {
        val root = Files.createTempDirectory("foldcode-build-pids").toFile()
        try {
            val eventFile = File(root, "session.events").apply { writeText("") }
            val native = File(root, "native").apply { mkdirs() }
            val tracker = File(native, "libfoldbuildpid.so").apply { writeText("test") }
            val session = BuildPidSession(eventFile) {}
            val environment = mutableMapOf("LD_PRELOAD" to "/existing/preload.so")

            session.configureNative(environment, native)

            assertEquals(eventFile.absolutePath, environment["FOLDCODE_BUILD_PID_FILE"])
            assertEquals("${tracker.absolutePath}:/existing/preload.so", environment["LD_PRELOAD"])
            session.close()
        } finally {
            root.deleteRecursively()
        }
    }
}
