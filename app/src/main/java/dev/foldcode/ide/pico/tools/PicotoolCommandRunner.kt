package dev.foldcode.ide

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal data class PicotoolCommandResult(
    val command: String,
    val output: String,
    val exitCode: Int,
)

/** Runs picotool without a shell, so command input cannot execute unrelated programs. */
internal class PicotoolCommandRunner(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = runtimeExecutable().isFile && runtimeLibrary().isFile

    fun runAsync(
        commandLine: String,
        workingDirectory: File?,
        status: (String) -> Unit,
        finished: (Result<PicotoolCommandResult>) -> Unit,
    ) {
        val device = findPicoDevice()
        when {
            device == null -> executeAsync(commandLine, workingDirectory, null, finished)
            usbManager.hasPermission(device) -> executeAsync(commandLine, workingDirectory, device, finished)
            else -> requestPermission(device, commandLine, workingDirectory, status, finished)
        }
    }

    private fun executeAsync(
        commandLine: String,
        workingDirectory: File?,
        device: UsbDevice?,
        finished: (Result<PicotoolCommandResult>) -> Unit,
    ) {
        thread(name = "FoldCode-picotool") {
            var connection: UsbDeviceConnection? = null
            val result = runCatching {
                connection = device?.let { usbManager.openDevice(it) ?: error("Could not open the Pico USB device") }
                run(commandLine, workingDirectory, connection)
            }
            connection?.close()
            main.post { finished(result) }
        }
    }

    private fun run(
        commandLine: String,
        workingDirectory: File?,
        usbConnection: UsbDeviceConnection?,
    ): PicotoolCommandResult {
        val parsed = parseArguments(commandLine)
        require(parsed.isNotEmpty()) { "Enter a picotool command" }
        val arguments = parsed.dropWhile { it == "picotool" }
        require(arguments.isNotEmpty()) { "Enter an argument, for example: info -a" }

        val runtimeExecutable = runtimeExecutable()
        require(runtimeExecutable.isFile && runtimeLibrary().isFile) { "The Pico extension runtime is unavailable" }
        require(runtimeExecutable.canExecute() || runtimeExecutable.setExecutable(true, false)) { "Picotool is not executable" }
        File(context.filesDir, "pico-tools-runtime/tmp").mkdirs()
        val executable = File(context.applicationInfo.nativeLibraryDir, "foldpicotool.so")
        require(executable.isFile && executable.canExecute()) { "The FoldCode picotool host is unavailable" }
        val command = listOf(executable.absolutePath) + arguments
        val usbDescriptor = usbConnection?.fileDescriptor?.takeIf { it >= 0 }
            ?.let(ParcelFileDescriptor::fromFd)
        val usbSocketName = usbDescriptor?.let {
            "foldcode.picotool.${android.os.Process.myPid()}.${System.nanoTime()}"
        }
        val usbServer = usbSocketName?.let(::LocalServerSocket)
        val processBuilder = ProcessBuilder(command)
            .directory(workingDirectory?.takeIf(File::isDirectory) ?: context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["FOLDCODE_PICOTOOL_ROOT"] = File(context.filesDir, "pico-tools-runtime").absolutePath
                environment()["LD_LIBRARY_PATH"] = listOf(
                    runtimeLibrary().parentFile?.absolutePath.orEmpty(),
                    File(context.filesDir, "usr/lib").absolutePath,
                ).joinToString(":")
                usbSocketName?.let { environment()["FOLDCODE_USB_SOCKET"] = it }
            }
        val process = try {
            processBuilder.start().also { child ->
                if (usbServer != null) {
                    handOffUsbDescriptor(usbServer, usbDescriptor, child)
                }
            }
        } catch (failure: Throwable) {
            usbServer?.close()
            usbDescriptor?.close()
            throw failure
        }

        val output = StringBuilder()
        val reader = thread(name = "FoldCode-picotool-output") {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(output) { output.appendLine(line) } }
            }
        }
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(2_000)
            throw IllegalStateException("picotool timed out after 120 seconds")
        }
        reader.join()
        val exitCode = process.exitValue()
        val text = synchronized(output) { output.toString().trimEnd() }
        return PicotoolCommandResult(
            command = arguments.joinToString(" "),
            output = text.ifBlank { "picotool finished with no output" },
            exitCode = exitCode,
        )
    }

    private fun handOffUsbDescriptor(
        server: LocalServerSocket,
        descriptor: ParcelFileDescriptor,
        process: Process,
    ) {
        val failure = AtomicReference<Throwable?>()
        val transfer = thread(name = "FoldCode-picotool-usb") {
            runCatching {
                server.accept().use { socket ->
                    socket.setFileDescriptorsForSend(arrayOf(descriptor.fileDescriptor))
                    socket.outputStream.write(1)
                    socket.outputStream.flush()
                }
            }.exceptionOrNull()?.let(failure::set)
        }
        transfer.join(USB_HANDOFF_TIMEOUT_MS)
        if (transfer.isAlive) {
            server.close()
            transfer.join(1_000)
            process.destroyForcibly()
            descriptor.close()
            error("Timed out while authorizing picotool USB access")
        }
        server.close()
        descriptor.close()
        failure.get()?.let {
            process.destroyForcibly()
            throw IllegalStateException("Could not authorize picotool USB access", it)
        }
    }

    private fun findPicoDevice(): UsbDevice? = usbManager.deviceList.values
        .filter { it.vendorId == RASPBERRY_PI_VENDOR_ID }
        .sortedBy { device ->
            when (device.productId) {
                RP2040_USBBOOT, RP2350_USBBOOT -> 0
                RP2040_STDIO_USB, RP2350_STDIO_USB -> 1
                else -> 2
            }
        }
        .firstOrNull()

    private fun requestPermission(
        device: UsbDevice,
        commandLine: String,
        workingDirectory: File?,
        status: (String) -> Unit,
        finished: (Result<PicotoolCommandResult>) -> Unit,
    ) {
        val action = "${context.packageName}.PICOTOOL_USB_PERMISSION"
        val completed = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                runCatching { context.unregisterReceiver(receiver) }
                finished(Result.failure(SecurityException("USB permission request timed out")))
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != action || !completed.compareAndSet(false, true)) return
                main.removeCallbacks(timeout)
                runCatching { context.unregisterReceiver(this) }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    executeAsync(commandLine, workingDirectory, device, finished)
                } else {
                    finished(Result.failure(SecurityException("USB permission was denied")))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(action))
        }
        status("Pico detected · waiting for USB permission…")
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            43,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        main.postDelayed(timeout, USB_PERMISSION_TIMEOUT_MS)
        runCatching { usbManager.requestPermission(device, permissionIntent) }
            .onFailure { error ->
                if (completed.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout)
                    runCatching { context.unregisterReceiver(receiver) }
                    finished(Result.failure(error))
                }
            }
    }

    private fun runtimeExecutable() = File(context.filesDir, "pico-tools-runtime/bin/picotool")

    private fun runtimeLibrary() = File(context.filesDir, "pico-tools-runtime/lib/libusb1.0.so")

    private fun parseArguments(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        value.trim().forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                quote != null && character == quote -> quote = null
                quote != null -> current.append(character)
                character == '\'' || character == '"' -> quote = character
                character.isWhitespace() -> if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        require(quote == null) { "Unclosed quote in command" }
        require(!escaped) { "Command cannot end with an escape character" }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private companion object {
        const val RASPBERRY_PI_VENDOR_ID = 0x2e8a
        const val RP2040_USBBOOT = 0x0003
        const val RP2350_USBBOOT = 0x000f
        const val RP2350_STDIO_USB = 0x0009
        const val RP2040_STDIO_USB = 0x000a
        const val USB_HANDOFF_TIMEOUT_MS = 5_000L
        const val USB_PERMISSION_TIMEOUT_MS = 30_000L
    }
}
