package dev.foldcode.ide

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Installs MicroPython firmware when necessary and uploads project Python files over raw REPL. */
internal class MicroPythonUsbRunner(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())
    private val flasher = PicoUsbFlasher(appContext)

    fun deploy(
        files: Map<String, String>,
        firmware: File,
        status: (String) -> Unit,
        finished: (Result<String>) -> Unit,
    ) {
        require(firmware.isFile) { "MicroPython firmware is unavailable" }
        val serial = findSerialTarget()
        if (serial != null) {
            authorizeAndUpload(serial, files, status, finished)
            return
        }
        status("No MicroPython REPL detected · checking BOOTSEL…")
        flasher.flash(firmware, status) { flashResult ->
            flashResult.fold(
                onSuccess = {
                    status("MicroPython firmware installed · waiting for USB REPL…")
                    waitForSerial(files, status, finished, attempts = 24)
                },
                onFailure = { finished(Result.failure(it)) },
            )
        }
    }

    private fun waitForSerial(
        files: Map<String, String>,
        status: (String) -> Unit,
        finished: (Result<String>) -> Unit,
        attempts: Int,
    ) {
        val target = findSerialTarget()
        when {
            target != null -> authorizeAndUpload(target, files, status, finished)
            attempts <= 0 -> finished(Result.success(
                "MicroPython firmware was installed. If the board does not reconnect automatically, reconnect it and press Run once more.",
            ))
            else -> main.postDelayed({ waitForSerial(files, status, finished, attempts - 1) }, 500)
        }
    }

    private fun authorizeAndUpload(
        target: SerialTarget,
        files: Map<String, String>,
        status: (String) -> Unit,
        finished: (Result<String>) -> Unit,
    ) {
        if (usbManager.hasPermission(target.device)) {
            uploadAsync(target, files, status, finished)
            return
        }
        status("Pico detected · waiting for USB permission…")
        val action = "${appContext.packageName}.MICROPYTHON_USB_PERMISSION"
        val completed = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                runCatching { appContext.unregisterReceiver(receiver) }
                finished(Result.failure(SecurityException("USB permission request timed out")))
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action || !completed.compareAndSet(false, true)) return
                main.removeCallbacks(timeout)
                runCatching { appContext.unregisterReceiver(this) }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    findSerialTarget()?.let { uploadAsync(it, files, status, finished) }
                        ?: finished(Result.failure(IllegalStateException("Pico disconnected before upload")))
                } else finished(Result.failure(SecurityException("USB permission was denied")))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") appContext.registerReceiver(receiver, IntentFilter(action))
        }
        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            44,
            Intent(action).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        main.postDelayed(timeout, USB_PERMISSION_TIMEOUT_MS)
        runCatching { usbManager.requestPermission(target.device, permissionIntent) }
            .onFailure { error ->
                if (completed.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout)
                    runCatching { appContext.unregisterReceiver(receiver) }
                    finished(Result.failure(error))
                }
            }
    }

    private fun uploadAsync(
        target: SerialTarget,
        files: Map<String, String>,
        status: (String) -> Unit,
        finished: (Result<String>) -> Unit,
    ) = thread(name = "FoldCode-MicroPython") {
        val result = runCatching { upload(target, files, status) }
        main.post { finished(result) }
    }

    private fun upload(target: SerialTarget, files: Map<String, String>, status: (String) -> Unit): String {
        val pythonFiles = files.filterKeys { path ->
            path.endsWith(".py", true) && !path.startsWith(".") && path.split('/').none { it == ".." }
        }
        require(pythonFiles.isNotEmpty()) { "This project has no Python files to upload" }
        val connection = usbManager.openDevice(target.device) ?: error("Could not open the Pico USB serial device")
        try {
            target.control?.let { connection.claimInterface(it, true) }
            require(connection.claimInterface(target.data, true)) { "Could not claim the MicroPython USB interface" }
            configureSerial(connection, target.control ?: target.data)
            val transport = SerialTransport(connection, target.input, target.output)
            post(status, "Entering MicroPython raw REPL…")
            transport.write(byteArrayOf(CTRL_C, CTRL_C, CTRL_A))
            require(transport.readUntil(">", 4_000).contains("raw REPL")) {
                "The connected Pico did not enter MicroPython raw REPL"
            }
            val ordered = pythonFiles.entries.sortedWith(compareBy<Map.Entry<String, String>> { it.key == "main.py" }.thenBy { it.key })
            ordered.forEachIndexed { fileIndex, (path, source) ->
                post(status, "Uploading ${fileIndex + 1}/${ordered.size} · $path")
                createParentDirectories(transport, path)
                val encoded = Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                execute(transport, "f=open('${pythonString(path)}','wb');f.close()")
                encoded.chunked(768).forEach { chunk ->
                    execute(transport, "import ubinascii;f=open('${pythonString(path)}','ab');f.write(ubinascii.a2b_base64('$chunk'));f.close()")
                }
            }
            post(status, "Upload complete · restarting Pico…")
            execute(transport, "import machine;machine.reset()", allowDisconnect = true)
            return "Uploaded ${ordered.size} MicroPython file${if (ordered.size == 1) "" else "s"} and restarted the Pico"
        } finally {
            connection.close()
        }
    }

    private fun createParentDirectories(transport: SerialTransport, path: String) {
        var current = ""
        path.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).forEach { part ->
            current = if (current.isEmpty()) part else "$current/$part"
            execute(transport, "import os\ntry: os.mkdir('${pythonString(current)}')\nexcept OSError: pass")
        }
    }

    private fun execute(transport: SerialTransport, command: String, allowDisconnect: Boolean = false) {
        transport.write(command.toByteArray(Charsets.UTF_8))
        transport.write(byteArrayOf(CTRL_D))
        val response = runCatching { transport.readUntil(">", 8_000) }.getOrElse {
            if (allowDisconnect) return else throw it
        }
        if (!response.startsWith("OK")) error("MicroPython rejected an upload command: ${response.take(160)}")
        val streams = response.removePrefix("OK").split(CTRL_D.toInt().toChar())
        val error = streams.getOrNull(1).orEmpty().trim()
        if (error.isNotBlank()) error(error)
    }

    private fun configureSerial(connection: UsbDeviceConnection, usbInterface: UsbInterface) {
        val lineCoding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(115_200).put(0).put(0).put(8).array()
        connection.controlTransfer(0x21, 0x20, 0, usbInterface.id, lineCoding, lineCoding.size, 1_000)
        connection.controlTransfer(0x21, 0x22, 3, usbInterface.id, null, 0, 1_000)
    }

    private fun findSerialTarget(): SerialTarget? = usbManager.deviceList.values
        .filter { it.vendorId == RASPBERRY_PI_VENDOR_ID }
        .firstNotNullOfOrNull { device ->
            var control: UsbInterface? = null
            repeat(device.interfaceCount) { index ->
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_COMM) control = candidate
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                    var input: UsbEndpoint? = null
                    var output: UsbEndpoint? = null
                    repeat(candidate.endpointCount) { endpointIndex ->
                        val endpoint = candidate.getEndpoint(endpointIndex)
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
                        }
                    }
                    if (input != null && output != null) return@firstNotNullOfOrNull SerialTarget(device, control, candidate, input!!, output!!)
                }
            }
            null
        }

    private fun pythonString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    private fun post(callback: (String) -> Unit, value: String) = main.post { callback(value) }

    private data class SerialTarget(
        val device: UsbDevice,
        val control: UsbInterface?,
        val data: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    private class SerialTransport(
        private val connection: UsbDeviceConnection,
        private val input: UsbEndpoint,
        private val output: UsbEndpoint,
    ) {
        fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val chunk = bytes.copyOfRange(offset, minOf(offset + output.maxPacketSize, bytes.size))
                val count = connection.bulkTransfer(output, chunk, chunk.size, 2_000)
                require(count > 0) { "MicroPython USB write failed" }
                offset += count
            }
        }

        fun readUntil(suffix: String, timeoutMs: Long): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            val result = StringBuilder()
            val buffer = ByteArray(maxOf(64, input.maxPacketSize))
            while (System.currentTimeMillis() < deadline) {
                val count = connection.bulkTransfer(input, buffer, buffer.size, 200)
                if (count > 0) {
                    result.append(String(buffer, 0, count, Charsets.UTF_8))
                    if (result.endsWith(suffix)) return result.toString()
                }
            }
            error("Timed out waiting for MicroPython")
        }
    }

    private companion object {
        const val USB_PERMISSION_TIMEOUT_MS = 30_000L
        const val RASPBERRY_PI_VENDOR_ID = 0x2e8a
        const val CTRL_A: Byte = 0x01
        const val CTRL_C: Byte = 0x03
        const val CTRL_D: Byte = 0x04
    }
}
