package dev.foldcode.ide

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most [maximumBytes], including on Android versions before InputStream.readNBytes. */
internal fun InputStream.readUpTo(maximumBytes: Int): ByteArray {
    require(maximumBytes >= 0) { "maximumBytes must not be negative" }
    val output = ByteArrayOutputStream(maximumBytes.coerceAtMost(DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maximumBytes
    while (remaining > 0) {
        val count = read(buffer, 0, buffer.size.coerceAtMost(remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}
