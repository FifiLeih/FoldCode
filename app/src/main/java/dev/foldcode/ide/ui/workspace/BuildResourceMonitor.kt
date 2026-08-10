package dev.foldcode.ide

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal data class BuildResourceStats(
    val deviceTemperatureCelsius: Float? = null,
    val appRamMiB: Int = 0,
    val buildRamMiB: Int? = null,
)

internal fun readBuildResourceStats(context: Context): BuildResourceStats {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val rawTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val temperature = rawTemperature
        ?.takeIf { it != Int.MIN_VALUE && it > 0 }
        ?.div(10f)
    val appRamKiB = Debug.getPss().toLong()
    val buildProcessIds = BuildPidRegistry.activeProcessIds()
        .filterTo(linkedSetOf()) { pid -> File("/proc/$pid/status").isFile }
    if (buildProcessIds.isEmpty()) {
        return BuildResourceStats(temperature, (appRamKiB / 1024f).roundToInt())
    }

    val processIds = linkedSetOf(Process.myPid()).apply { addAll(buildProcessIds) }.toList()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memoryInfo = runCatching {
        activityManager?.getProcessMemoryInfo(processIds.toIntArray()).orEmpty()
    }.getOrDefault(emptyArray())
    var measuredProcesses = 0
    var totalMemoryKiB = 0L
    processIds.indices.forEach { index ->
        val activityManagerPss = memoryInfo.getOrNull(index)?.totalPss?.toLong()?.takeIf { it > 0 }
        val procMemory = if (activityManagerPss == null && processIds[index] != Process.myPid()) {
            readProcProcessMemory(processIds[index], Process.myUid())
        } else {
            null
        }
        val memoryKiB = activityManagerPss
            ?: procMemory?.pssKiB
            ?: procMemory?.rssKiB
            ?: if (processIds[index] == Process.myPid()) appRamKiB else null
        if (memoryKiB != null && memoryKiB > 0) {
            measuredProcesses += 1
            totalMemoryKiB += memoryKiB
        }
    }
    val appRamMiB = (appRamKiB / 1024f).roundToInt()
    val buildRamMiB = totalMemoryKiB.takeIf { measuredProcesses >= 2 }
        ?.let { (it / 1024f).roundToInt() }
    return BuildResourceStats(temperature, appRamMiB, buildRamMiB)
}

internal fun BuildResourceStats.compactLabel(): String = listOfNotNull(
    deviceTemperatureCelsius?.let { String.format(Locale.US, "Temp %.1f°C", it) },
    buildRamMiB?.takeIf { it > 0 }?.let { "Build RAM ${formatBuildMemory(it)}" }
        ?: appRamMiB.takeIf { it > 0 }?.let { "App RAM ${formatBuildMemory(it)}" },
).joinToString(" · ")

internal fun formatBuildMemory(mebibytes: Int): String = when {
    mebibytes >= 1024 -> String.format(Locale.US, "%.1f GB", mebibytes / 1024f)
    else -> "$mebibytes MB"
}

internal data class ProcProcessMemory(
    val pssKiB: Long? = null,
    val rssKiB: Long? = null,
)

internal data class ProcProcessStatus(
    val uid: Int? = null,
    val rssKiB: Long? = null,
)

internal fun parseProcProcessStatus(contents: String): ProcProcessStatus {
    var uid: Int? = null
    var rssKiB: Long? = null
    contents.lineSequence().forEach { line ->
        when {
            line.startsWith("Uid:") -> uid = line.substringAfter(':')
                .trim()
                .takeWhile { !it.isWhitespace() }
                .toIntOrNull()
            line.startsWith("VmRSS:") -> rssKiB = line.substringAfter(':')
                .trim()
                .takeWhile { !it.isWhitespace() }
                .toLongOrNull()
                ?.takeIf { it > 0 }
        }
    }
    return ProcProcessStatus(uid, rssKiB)
}

internal fun parseProcSmapsRollupPss(contents: String): Long? = contents.lineSequence()
    .firstOrNull { it.startsWith("Pss:") }
    ?.substringAfter(':')
    ?.trim()
    ?.takeWhile { !it.isWhitespace() }
    ?.toLongOrNull()
    ?.takeIf { it > 0 }

private fun readProcProcessMemory(pid: Int, expectedUid: Int): ProcProcessMemory? {
    val status = runCatching {
        parseProcProcessStatus(File("/proc/$pid/status").readText())
    }.getOrNull() ?: return null
    if (status.uid != expectedUid) return null
    val pssKiB = runCatching {
        parseProcSmapsRollupPss(File("/proc/$pid/smaps_rollup").readText())
    }.getOrNull()
    return ProcProcessMemory(pssKiB = pssKiB, rssKiB = status.rssKiB)
}
