package dev.foldcode.ide

import android.os.Handler
import android.os.Looper

private val mainThreadHandler by lazy(LazyThreadSafetyMode.PUBLICATION) {
    Handler(Looper.getMainLooper())
}

/** Posts state changes produced by toolchain workers back to Compose's UI thread. */
internal fun postToMainThread(action: () -> Unit) {
    mainThreadHandler.post(action)
}
