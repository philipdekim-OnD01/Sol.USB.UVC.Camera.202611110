package edu.yonsei.cx3viewer

import android.hardware.usb.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class VendorCommand(
    val requestType: Int, val request: Int, val value: Int, val index: Int,
    val payload: ByteArray = byteArrayOf(), val readLength: Int = 0
)

/** Android USB Host implementation. No undocumented firmware opcodes are assumed. */
class UsbCameraController(private val manager: UsbManager) {
    var connection: UsbDeviceConnection? = null
        private set
    private var intf: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private val running = AtomicBoolean(false)

    fun open(device: UsbDevice): String {
        close()
        val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }
        val pair = candidates.flatMap { i ->
            (0 until i.endpointCount).map { i to i.getEndpoint(it) }
        }.firstOrNull { (_, ep) -> ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN }
            ?: error("No Bulk IN endpoint. UVC isochronous mode is not supported.")
        val conn = manager.openDevice(device) ?: error("USB open failed; check permission")
        if (!conn.claimInterface(pair.first, false)) {
            conn.close(); error("Cannot claim interface; another app may own it")
        }
        connection = conn
        intf = pair.first
        bulkIn = pair.second
        return "Claimed interface ${pair.first.id}, Bulk IN endpoint 0x%02X".format(pair.second.address)
    }

    /** bmRequestType must be specified by firmware docs or a verified USB trace. */
    fun control(command: VendorCommand): ByteArray {
        val conn = connection ?: error("Not connected")
        require(command.requestType in 0..255 && command.request in 0..255)
        require(command.value in 0..65535 && command.index in 0..65535)
        require((command.requestType and 0x60) == 0x40) { "Only vendor-type requests allowed" }
        val isIn = (command.requestType and 0x80) != 0
        val buffer = if (isIn) ByteArray(command.readLength.coerceIn(0, 4096)) else command.payload
        val length = conn.controlTransfer(command.requestType, command.request, command.value, command.index, buffer, buffer.size, 2000)
        if (length < 0) error("Control transfer failed: request=0x%02X".format(command.request))
        return if (isIn) buffer.copyOf(length) else byteArrayOf()
    }

    /** Fixed-length RAW frames. No frame headers, padding, metadata, or resync detection. */
    fun capture(width: Int, height: Int, format: PixelFormat, onFrame: (ByteArray, Long) -> Unit) {
        val conn = connection ?: error("Not connected")
        val ep = bulkIn ?: error("No Bulk IN endpoint")
        val frameBytes = RawFrameDecoder.expectedBytes(width, height, format)
        require(frameBytes > 0)
        running.set(true)
        val buffer = ByteArray(64 * 1024)
        var frame = ByteArray(frameBytes)
        var filled = 0
        var frames = 0L
        while (running.get()) {
            val n = conn.bulkTransfer(ep, buffer, buffer.size, 250)
            if (n <= 0) continue
            var offset = 0
            while (offset < n) {
                val take = minOf(n - offset, frameBytes - filled)
                System.arraycopy(buffer, offset, frame, filled, take)
                offset += take; filled += take
                if (filled == frameBytes) {
                    frames++
                    onFrame(frame, frames)
                    frame = ByteArray(frameBytes)
                    filled = 0
                }
            }
        }
    }

    fun stop() { running.set(false) }
    fun close() {
        stop()
        val conn = connection
        val iface = intf
        connection = null; intf = null; bulkIn = null
        if (conn != null) {
            if (iface != null) runCatching { conn.releaseInterface(iface) }
            runCatching { conn.close() }
        }
    }
}
