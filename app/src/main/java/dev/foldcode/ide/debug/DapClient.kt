package dev.foldcode.ide

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Small Debug Adapter Protocol transport shared by LLDB and Pico SWD sessions. */
internal interface DapConnection : Closeable {
    fun request(command: String, arguments: JSONObject = JSONObject(), reply: (JSONObject) -> Unit = {}): Int
}

internal class DapClient(
    private val process: Process,
    private val onEvent: (String, JSONObject) -> Unit,
    private val onClosed: (Int) -> Unit,
) : DapConnection {
    private val input = BufferedInputStream(process.inputStream)
    private val output = BufferedOutputStream(process.outputStream)
    private val nextSequence = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val writeLock = Any()
    @Volatile private var closed = false

    init {
        thread(name = "FoldCode-DAP-reader", isDaemon = true) { readLoop() }
    }

    override fun request(command: String, arguments: JSONObject, reply: (JSONObject) -> Unit): Int {
        val sequence = nextSequence.getAndIncrement()
        pending[sequence] = reply
        send(JSONObject()
            .put("seq", sequence)
            .put("type", "request")
            .put("command", command)
            .put("arguments", arguments))
        return sequence
    }

    private fun send(message: JSONObject) {
        val body = message.toString().toByteArray(StandardCharsets.UTF_8)
        synchronized(writeLock) {
            check(!closed) { "Debug adapter is closed" }
            output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                var contentLength = -1
                while (true) {
                    val line = readAsciiLine() ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
                }
                if (contentLength < 0) continue
                val bytes = ByteArray(contentLength)
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) return
                    offset += count
                }
                dispatch(JSONObject(String(bytes, StandardCharsets.UTF_8)))
            }
        } catch (_: Throwable) {
            // close() deliberately interrupts a blocking read. Never allow a
            // normal adapter shutdown to escape this thread and crash Android.
        } finally {
            val exit = runCatching { process.waitFor() }.getOrDefault(-1)
            if (!closed) onClosed(exit)
        }
    }

    private fun readAsciiLine(): String? {
        val value = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return value.toString().trimEnd('\r')
            value.append(byte.toChar())
        }
    }

    private fun dispatch(message: JSONObject) {
        when (message.optString("type")) {
            "response" -> pending.remove(message.optInt("request_seq"))?.invoke(message)
            "event" -> onEvent(message.optString("event"), message.optJSONObject("body") ?: JSONObject())
        }
    }

    override fun close() {
        closed = true
        pending.clear()
        runCatching { output.close() }
        runCatching { input.close() }
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }
}

/** TCP variant used by vscode-js-debug, whose standalone DAP server does not use stdio. */
internal class TcpDapClient(
    private val socket: Socket,
    private val onEvent: (String, JSONObject) -> Unit,
    private val onClosed: () -> Unit,
) : DapConnection {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val nextSequence = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val writeLock = Any()
    @Volatile private var closed = false

    init { thread(name = "FoldCode-V8-DAP-reader", isDaemon = true) { readLoop() } }

    override fun request(command: String, arguments: JSONObject, reply: (JSONObject) -> Unit): Int {
        val sequence = nextSequence.getAndIncrement()
        pending[sequence] = reply
        val body = JSONObject().put("seq", sequence).put("type", "request")
            .put("command", command).put("arguments", arguments).toString().toByteArray(StandardCharsets.UTF_8)
        synchronized(writeLock) {
            check(!closed) { "Debug adapter is closed" }
            output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(body)
            output.flush()
        }
        return sequence
    }

    private fun readLoop() {
        try {
            while (!closed) {
                var length = -1
                while (true) {
                    val line = readLine() ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toIntOrNull() ?: -1
                }
                if (length < 0) continue
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    if (count < 0) return
                    offset += count
                }
                val message = JSONObject(String(bytes, StandardCharsets.UTF_8))
                when (message.optString("type")) {
                    "response" -> pending.remove(message.optInt("request_seq"))?.invoke(message)
                    "event" -> onEvent(message.optString("event"), message.optJSONObject("body") ?: JSONObject())
                }
            }
        } catch (_: Throwable) {
        } finally {
            if (!closed) onClosed()
        }
    }

    private fun readLine(): String? {
        val value = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return value.toString().trimEnd('\r')
            value.append(byte.toChar())
        }
    }

    override fun close() {
        closed = true
        pending.clear()
        runCatching { socket.close() }
    }
}
