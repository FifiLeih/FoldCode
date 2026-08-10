package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustCargoHostBuildTest {
    @Test
    fun hostBuildOverridesKeepDebugSymbolsOutOfBuildScriptsOnly() {
        val environment = mutableMapOf<String, String>()

        configureRustCargoHostBuildEnvironment(environment)

        assertEquals("0", environment["CARGO_PROFILE_DEV_BUILD_OVERRIDE_DEBUG"])
        assertEquals("0", environment["CARGO_PROFILE_RELEASE_BUILD_OVERRIDE_DEBUG"])
        assertFalse("Firmware dev profile must remain untouched", "CARGO_PROFILE_DEV_DEBUG" in environment)
        assertFalse("Firmware release profile must remain untouched", "CARGO_PROFILE_RELEASE_DEBUG" in environment)
    }

    @Test
    fun hostLinkerSigsegvIsRecoverableWithoutMaskingNormalLinkErrors() {
        assertTrue(
            isRustHostLinkerCrash(
                "error: linking with `/usr/bin/cc` failed: signal: 11 (SIGSEGV)",
            ),
        )
        assertFalse(isRustHostLinkerCrash("error: linking with `/usr/bin/cc` failed: exit status: 1"))
        assertFalse(isRustHostLinkerCrash("error: undefined reference to `main`"))
    }

    @Test
    fun rustcSigsegvRetainsItsSeparateCacheRecoveryPath() {
        assertTrue(isRustHostCompilerCrash("rustc interrupted by SIGSEGV, printing backtrace"))
        assertFalse(isRustHostCompilerCrash("error[E0308]: mismatched types"))
    }
}
