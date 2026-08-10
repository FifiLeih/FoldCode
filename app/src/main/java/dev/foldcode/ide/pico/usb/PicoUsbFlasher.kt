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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Direct UF2-over-USB-MSC flashing for RP2040 and RP2350 BOOTSEL devices. */
internal class PicoUsbFlasher(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())

    fun flash(uf2: File, status: (String) -> Unit, finished: (Result<Unit>) -> Unit) {
        val target = findPicoMassStorage()
        if (target == null) {
            val connectedPico = usbManager.deviceList.values.any { it.vendorId == RASPBERRY_PI_VENDOR_ID }
            finished(Result.failure(IllegalStateException(
                if (connectedPico) "Pico detected, but it is not in BOOTSEL mass-storage mode"
                else "No Pico detected. Hold BOOTSEL while connecting its USB cable",
            )))
            return
        }
        if (usbManager.hasPermission(target.device)) {
            writeAsync(target, uf2, status, finished)
        } else {
            status("Pico detected · waiting for USB permission…")
            requestPermission(target, uf2, status, finished)
        }
    }

    private fun findPicoMassStorage(): MassStorageTarget? {
        usbManager.deviceList.values
            .filter { it.vendorId == RASPBERRY_PI_VENDOR_ID }
            .forEach { device ->
                repeat(device.interfaceCount) { index ->
                    val usbInterface = device.getInterface(index)
                    if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE ||
                        usbInterface.interfaceSubclass != 0x06 || usbInterface.interfaceProtocol != 0x50
                    ) return@repeat
                    var input: UsbEndpoint? = null
                    var output: UsbEndpoint? = null
                    repeat(usbInterface.endpointCount) { endpointIndex ->
                        val endpoint = usbInterface.getEndpoint(endpointIndex)
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
                        }
                    }
                    if (input != null && output != null) return MassStorageTarget(device, usbInterface, input!!, output!!)
                }
            }
        return null
    }

    private fun requestPermission(
        target: MassStorageTarget,
        uf2: File,
        status: (String) -> Unit,
        finished: (Result<Unit>) -> Unit,
    ) {
        val action = "${appContext.packageName}.PICO_USB_PERMISSION"
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
                    writeAsync(target, uf2, status, finished)
                } else {
                    finished(Result.failure(SecurityException("USB permission was denied")))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") appContext.registerReceiver(receiver, IntentFilter(action))
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            42,
            Intent(action).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        main.postDelayed(timeout, USB_PERMISSION_TIMEOUT_MS)
        runCatching { usbManager.requestPermission(target.device, pendingIntent) }
            .onFailure { error ->
                if (completed.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout)
                    runCatching { appContext.unregisterReceiver(receiver) }
                    finished(Result.failure(error))
                }
            }
    }

    private fun writeAsync(
        target: MassStorageTarget,
        uf2: File,
        status: (String) -> Unit,
        finished: (Result<Unit>) -> Unit,
    ) {
        Thread {
            var completedBlocks = 0
            val result = runCatching {
                val firmware = uf2.readBytes()
                require(firmware.isNotEmpty() && firmware.size % UF2_BLOCK_SIZE == 0) { "Invalid UF2 file size" }
                validateUf2(firmware)
                post(status, "Pico detected · opening BOOTSEL transport…")
                val connection = usbManager.openDevice(target.device) ?: error("Could not open Pico USB device")
                try {
                    require(connection.claimInterface(target.usbInterface, true)) { "Could not claim Pico mass-storage interface" }
                    val transport = BulkOnlyTransport(connection, target.input, target.output)
                    val totalBlocks = firmware.size / UF2_BLOCK_SIZE
                    val firstDataLba = findFatDataArea(transport)
                    post(status, "Pico BOOTSEL volume ready · writing firmware…")
                    repeat(totalBlocks) { index ->
                        var dataSent = false
                        val block = firmware.copyOfRange(index * UF2_BLOCK_SIZE, (index + 1) * UF2_BLOCK_SIZE)
                        try {
                            // The BOOTSEL drive is synthetic: ROM examines every written
                            // 512-byte sector for UF2 magic and ignores filesystem metadata.
                            transport.writeBlock(firstDataLba + index, block) { dataSent = true }
                        } catch (error: Exception) {
                            // The BOOTSEL device normally disconnects immediately after
                            // accepting the final UF2 block, before returning its CSW.
                            if (!(dataSent && index == totalBlocks - 1)) throw error
                        }
                        completedBlocks = index + 1
                        val percent = completedBlocks * 100 / totalBlocks
                        post(status, "Flashing $percent% · ${completedBlocks * UF2_BLOCK_SIZE / 1024}/${firmware.size / 1024} KB")
                    }
                    // Some ROM/host combinations do not disconnect until the host
                    // explicitly flushes and ejects the synthetic drive.
                    runCatching { transport.synchronizeCache() }
                    runCatching { transport.eject() }
                } finally {
                    connection.close()
                }
                Unit
            }
            val normalized = if (result.isFailure && completedBlocks * UF2_BLOCK_SIZE == uf2.length().toInt()) {
                Result.success(Unit)
            } else result
            main.post { finished(normalized) }
        }.start()
    }

    private fun validateUf2(bytes: ByteArray) {
        val first = ByteBuffer.wrap(bytes, 0, UF2_BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        require(first.int == 0x0A324655 && first.int == 0x9E5D5157.toInt()) { "UF2 magic is invalid" }
        require(ByteBuffer.wrap(bytes, UF2_BLOCK_SIZE - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int == 0x0AB16F30) {
            "UF2 block footer is invalid"
        }
    }

    private fun findFatDataArea(transport: BulkOnlyTransport): Int {
        val lastLba = transport.readCapacityLastLba()
        require(lastLba > 0) { "Pico reported an empty BOOTSEL disk" }
        val sector0 = transport.readBlock(0)
        val candidates = buildList {
            add(0)
            // RP2350 normally exposes a partitioned synthetic disk. Honour every
            // non-empty MBR entry, then fall back to scanning the small header area.
            if (sector0.u16(510) == 0xaa55) {
                repeat(4) { index ->
                    val entry = 446 + index * 16
                    val start = sector0.u32(entry + 8)
                    if (sector0[entry + 4].toInt() != 0 && start in 1..lastLba) add(start)
                }
            }
            for (lba in 1..minOf(lastLba, 128)) add(lba)
        }.distinct()

        candidates.forEach { bootLba ->
            val boot = if (bootLba == 0) sector0 else transport.readBlock(bootLba)
            fatDataLba(boot, bootLba)?.let { dataLba ->
                if (dataLba <= lastLba) return dataLba
            }
        }
        error("Pico BOOTSEL volume has no recognizable FAT data area")
    }

    private fun fatDataLba(boot: ByteArray, bootLba: Int): Int? {
        if (boot.size != UF2_BLOCK_SIZE || boot.u16(510) != 0xaa55) return null
        val bytesPerSector = boot.u16(11)
        val sectorsPerCluster = boot[13].toInt() and 0xff
        val reservedSectors = boot.u16(14)
        val fatCount = boot[16].toInt() and 0xff
        val rootEntries = boot.u16(17)
        val sectorsPerFat16 = boot.u16(22)
        val sectorsPerFat32 = boot.u32(36)
        if (bytesPerSector != UF2_BLOCK_SIZE || sectorsPerCluster !in setOf(1, 2, 4, 8, 16, 32, 64, 128) ||
            reservedSectors <= 0 || fatCount !in 1..2
        ) return null
        val sectorsPerFat = if (sectorsPerFat16 > 0) sectorsPerFat16 else sectorsPerFat32
        if (sectorsPerFat <= 0) return null
        val rootDirectorySectors = ((rootEntries * 32) + bytesPerSector - 1) / bytesPerSector
        return bootLba + reservedSectors + fatCount * sectorsPerFat + rootDirectorySectors
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32(offset: Int): Int =
        u16(offset) or (u16(offset + 2) shl 16)

    private fun post(callback: (String) -> Unit, value: String) = main.post { callback(value) }

    private data class MassStorageTarget(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    private class BulkOnlyTransport(
        private val connection: UsbDeviceConnection,
        private val input: UsbEndpoint,
        private val output: UsbEndpoint,
    ) {
        private val nextTag = AtomicInteger(1)

        fun writeBlock(lba: Int, data: ByteArray, sent: () -> Unit) {
            command(scsiWrite10(lba), data, inputDirection = false, dataSent = sent)
        }

        fun readBlock(lba: Int): ByteArray = ByteArray(UF2_BLOCK_SIZE).also {
            command(scsiRead10(lba), it, inputDirection = true)
        }

        fun readCapacityLastLba(): Int {
            val response = ByteArray(8)
            command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), response, inputDirection = true)
            return ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN).int
        }

        fun synchronizeCache() = command(
            byteArrayOf(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            ByteArray(0),
            inputDirection = false,
        )

        fun eject() = command(
            byteArrayOf(0x1b, 0, 0, 0, 0x02, 0),
            ByteArray(0),
            inputDirection = false,
        )

        private fun command(
            cdb: ByteArray,
            data: ByteArray,
            inputDirection: Boolean,
            dataSent: () -> Unit = {},
        ) {
            val tag = nextTag.getAndIncrement()
            val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(CBW_SIGNATURE)
                putInt(tag)
                putInt(data.size)
                put(if (inputDirection) 0x80.toByte() else 0)
                put(0)
                put(cdb.size.toByte())
                put(cdb)
                while (position() < capacity()) put(0)
            }.array()
            transfer(output, cbw)
            if (inputDirection) transfer(input, data) else {
                transfer(output, data)
                dataSent()
            }
            val csw = ByteArray(13)
            transfer(input, csw)
            val wrapper = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
            require(wrapper.int == CSW_SIGNATURE) { "Pico returned an invalid USB status" }
            require(wrapper.int == tag) { "Pico USB command tag mismatch" }
            wrapper.int // residue
            require((wrapper.get().toInt() and 0xff) == 0) { "Pico rejected a USB storage command" }
        }

        private fun transfer(endpoint: UsbEndpoint, bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = connection.bulkTransfer(endpoint, bytes, offset, bytes.size - offset, USB_TIMEOUT_MS)
                require(count > 0) { "Pico USB transfer stopped at $offset/${bytes.size} bytes" }
                offset += count
            }
        }

        private fun scsiWrite10(lba: Int) = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x2A)
            put(0)
            putInt(lba)
            put(0)
            putShort(1)
            put(0)
        }.array()

        private fun scsiRead10(lba: Int) = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x28)
            put(0)
            putInt(lba)
            put(0)
            putShort(1)
            put(0)
        }.array()

        private companion object {
            const val CBW_SIGNATURE = 0x43425355
            const val CSW_SIGNATURE = 0x53425355
            const val USB_TIMEOUT_MS = 5000
        }
    }

    private companion object {
        const val USB_PERMISSION_TIMEOUT_MS = 30_000L
        const val RASPBERRY_PI_VENDOR_ID = 0x2e8a
        const val UF2_BLOCK_SIZE = 512
    }
}
