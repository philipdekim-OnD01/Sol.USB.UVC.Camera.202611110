package edu.yonsei.cx3viewer

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Proof of concept: enumerates USB interfaces and reads a vendor bulk IN endpoint.
 * It does not implement CX3 start/stop commands, framing, or RAW10/12 unpacking.
 */
class MainActivity : Activity() {
    private val permissionAction = "edu.yonsei.cx3viewer.USB_PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var connection: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var active = false
    private var chosen: UsbDevice? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                log(if (granted) "USB permission granted" else "USB permission denied")
                if (granted) chosen?.let { openAndRead(it) }
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) stop()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        status = TextView(this).apply { text = "Connect CX3 via USB OTG. Protocol is not yet known." }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val refresh = Button(this).apply { text = "Find USB devices"; setOnClickListener { enumerate() } }
        val stopButton = Button(this).apply { text = "Stop capture"; setOnClickListener { stop() } }
        root.addView(status); root.addView(refresh); root.addView(stopButton); root.addView(ScrollView(this).apply { addView(devices) })
        setContentView(root)
        val filter = IntentFilter().apply { addAction(permissionAction); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        enumerate()
    }
    private fun log(message: String) = ui.post { status.text = message }
    private fun enumerate() {
        devices.removeAllViews()
        for (device in usb.deviceList.values) {
            val details = buildString {
                append("VID=%04X PID=%04X interfaces=%d\n".format(device.vendorId, device.productId, device.interfaceCount))
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    append("IF ${intf.id}: class=${intf.interfaceClass}, endpoints=${intf.endpointCount}\n")
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        append("  EP 0x%02X type=%d direction=%s maxPacket=%d\n".format(ep.address, ep.type, if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", ep.maxPacketSize))
                    }
                }
            }
            devices.addView(Button(this).apply { text = details + "\nOpen bulk IN (diagnostic only)"; isAllCaps = false
                setOnClickListener { chosen = device; if (usb.hasPermission(device)) openAndRead(device) else {
                    val intent = Intent(permissionAction).setPackage(packageName)
                    val pi = PendingIntent.getBroadcast(this@MainActivity, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    usb.requestPermission(device, pi)
                } }
            })
        }
        if (usb.deviceList.isEmpty()) log("No USB devices detected. Check OTG/host and cable.")
    }
    private fun openAndRead(device: UsbDevice) {
        stop()
        val pair = (0 until device.interfaceCount).asSequence().map { device.getInterface(it) }.flatMap { intf ->
            (0 until intf.endpointCount).asSequence().map { intf to intf.getEndpoint(it) }
        }.firstOrNull { (intf, ep) -> intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN }
        if (pair == null) { log("No vendor bulk IN endpoint. UVC requires a different implementation."); return }
        val (intf, endpoint) = pair
        val conn = usb.openDevice(device) ?: run { log("Unable to open device"); return }
        if (!conn.claimInterface(intf, false)) { conn.close(); log("Unable to claim interface"); return }
        connection = conn; claimed = intf; active = true
        worker.execute {
            val file = File(getExternalFilesDir(null), "cx3_capture_${System.currentTimeMillis()}.bin")
            var received = 0L
            try {
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(16384)
                    val deadline = System.currentTimeMillis() + 5000
                    while (active && System.currentTimeMillis() < deadline && received < 32L * 1024 * 1024) {
                        val n = conn.bulkTransfer(endpoint, buffer, buffer.size, 500)
                        if (n > 0) { output.write(buffer, 0, n); received += n }
                    }
                }
                log("Captured $received bytes to ${file.absolutePath}. Zero bytes may mean a stream-start command is required.")
            } catch (e: Exception) { log("Capture failed: ${e.message}") }
            finally { active = false; try { conn.releaseInterface(intf) } catch (_: Exception) {}; conn.close(); if (connection === conn) connection = null }
        }
    }
    private fun stop() { active = false; /* worker closes its own connection after timeout */ }
    override fun onDestroy() { stop(); unregisterReceiver(receiver); worker.shutdown(); super.onDestroy() }
}
