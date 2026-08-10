package dev.foldcode.ide

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Exposes this process' WebView DevTools abstract socket to js-debug on loopback. */
internal class WebViewDevToolsBridge : Closeable {
    // js-debug is configured with an IPv4 CDP address. Bind explicitly to the
    // same family because getLoopbackAddress() may return ::1 on Android.
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = Collections.synchronizedSet(mutableSetOf<Closeable>())
    @Volatile private var closed = false

    val port: Int = server.localPort

    init {
        Thread(::acceptConnections, "FoldCode-WebView-CDP").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptConnections() {
        while (!closed) {
            val tcp = runCatching { server.accept() }.getOrNull() ?: break
            Thread({ bridge(tcp) }, "FoldCode-WebView-CDP-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun bridge(tcp: Socket) {
        val local = LocalSocket()
        clients += tcp
        clients += local
        try {
            local.connect(LocalSocketAddress(
                "webview_devtools_remote_${Process.myPid()}",
                LocalSocketAddress.Namespace.ABSTRACT,
            ))
            val upstream = Thread {
                runCatching { tcp.getInputStream().copyTo(local.outputStream) }
                runCatching { local.shutdownOutput() }
            }.apply { isDaemon = true; start() }
            runCatching { local.inputStream.copyTo(tcp.getOutputStream()) }
            runCatching { tcp.shutdownOutput() }
            upstream.join(500)
        } finally {
            clients -= tcp
            clients -= local
            runCatching { tcp.close() }
            runCatching { local.close() }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
        clients.clear()
    }
}
