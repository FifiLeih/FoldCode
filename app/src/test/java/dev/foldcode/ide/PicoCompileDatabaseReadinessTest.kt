package dev.foldcode.ide

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PicoCompileDatabaseReadinessTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun stalePrivateSdkAndSysrootPathsDisablePicoDiagnostics() {
        val project = temporary.newFolder("project")
        val privateData = temporary.newFolder("private-data")
        val sdk = File(privateData, "files/pico-cmake-sdk/sdk")
        val sysroot = File(privateData, "files/pico-cmake-sysroot-rp2040")
        val database = File(project, "compile_commands.json")
        database.writeText(
            JSONArray().put(
                JSONObject()
                    .put("directory", project.absolutePath)
                    .put("file", File(project, "main.cpp").absolutePath)
                    .put(
                        "command",
                        "clang++ -isystem '${sdk.absolutePath}/src/common' " +
                            "--sysroot=${sysroot.absolutePath} -c main.cpp",
                    ),
            ).toString(),
        )

        assertFalse(isPicoCompileDatabaseUsable(database, listOf(privateData)))

        File(sdk, "src/common").mkdirs()
        File(sdk, "pico_sdk_init.cmake").writeText("# ready")
        sysroot.mkdirs()
        File(sysroot, ".version").writeText("ready")
        assertTrue(isPicoCompileDatabaseUsable(database, listOf(privateData)))
    }

    @Test
    fun argumentsFormAndDataDirectoryAliasAreValidated() {
        val project = temporary.newFolder("arguments-project")
        val privateData = temporary.newFolder("arguments-private")
        val resourceDirectory = File(privateData, "files/cpp-runtime/toolchain/lib/clang/21")
        val database = File(project, "compile_commands.json")
        database.writeText(
            JSONArray().put(
                JSONObject()
                    .put("directory", project.absolutePath)
                    .put("file", File(project, "main.cpp").absolutePath)
                    .put(
                        "arguments",
                        JSONArray(listOf("clang++", "-resource-dir=${resourceDirectory.absolutePath}", "main.cpp")),
                    ),
            ).toString(),
        )

        assertFalse(isPicoCompileDatabaseUsable(database, listOf(privateData)))
        resourceDirectory.mkdirs()
        assertTrue(isPicoCompileDatabaseUsable(database, listOf(privateData)))
    }

    @Test
    fun picoUmbrellaHeaderSuggestionIsRecognizedWithoutHidingRealErrors() {
        assertTrue(isClangdUnusedIncludeDiagnostic("Included header stdlib.h is not used directly (fix available)"))
        assertFalse(isClangdUnusedIncludeDiagnostic("In included file: 'cassert' file not found"))
    }
}
