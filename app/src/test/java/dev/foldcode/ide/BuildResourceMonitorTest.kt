package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuildResourceMonitorTest {
    @Test
    fun compactLabelIdentifiesAppRamWithoutClaimingToolchainUsage() {
        val stats = BuildResourceStats(
            deviceTemperatureCelsius = 35.4f,
            appRamMiB = 96,
        )

        assertEquals("Temp 35.4°C · App RAM 96 MB", stats.compactLabel())
    }

    @Test
    fun compactLabelUsesBuildRamWhenGuestProcessesAreMeasured() {
        val stats = BuildResourceStats(
            deviceTemperatureCelsius = 36.1f,
            appRamMiB = 96,
            buildRamMiB = 418,
        )

        assertEquals("Temp 36.1°C · Build RAM 418 MB", stats.compactLabel())
    }

    @Test
    fun parsesUidAndResidentMemoryFromProcStatus() {
        val status = parseProcProcessStatus(
            """
            Name: rustc
            Uid: 10627 10627 10627 10627
            VmRSS: 95008 kB
            """.trimIndent(),
        )

        assertEquals(10627, status.uid)
        assertEquals(95_008L, status.rssKiB)
    }

    @Test
    fun parsesPssFromSmapsRollup() {
        val pss = parseProcSmapsRollupPss(
            """
            0000000000000000-ffffffffff601000 ---p 00000000 00:00 0 [rollup]
            Rss:              105420 kB
            Pss:               93417 kB
            Pss_Dirty:         62140 kB
            """.trimIndent(),
        )

        assertEquals(93_417L, pss)
        assertNull(parseProcSmapsRollupPss("Rss: 1024 kB"))
    }
}
