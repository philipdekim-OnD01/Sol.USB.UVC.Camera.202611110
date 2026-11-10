package edu.yonsei.cx3viewer

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.hardware.usb.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Live diagnostic preview and configurable vendor control transfers for CX3 USB Host. */
class MainActivity : Activity() {
    private val permissionAction = "edu.yonsei.cx3viewer.USB_PERMISSION"
    private lateinit var manager: UsbManager
    private lateinit var camera: UsbCameraController
    private lateinit var logView: TextView
    private lateinit var image: ImageView
    private lateinit var deviceList: LinearLayout
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var formatInput: Spinner
    private lateinit var requestType: EditText
    private lateinit var request: EditText
    private lateinit var value: EditText
    private lateinit var index: EditText
    private lateinit var payload: EditText
    private lateinit var readLength: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val previewBusy = AtomicBoolean(false)
    private var selected: UsbDevice? = null
    private var streaming = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    selected?.let { connect(it) }
                } else note("USB permission denied")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    camera.stop()
                    note("Device detached. Restart the app before reconnecting.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(USB_SERVICE) as UsbManager
        camera = UsbCameraController(manager)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)
        root.addView(TextView(this).apply { text = "CX3 Live RAW Preview (experimental)"; textSize = 21f })
        logView = TextView(this).apply { text = "Connect USB camera. Use verified vendor commands only." }
        root.addView(logView)
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = 260
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420)
        }
        root.addView(image)
        root.addView(Button(this).apply { text = "Refresh USB devices"; setOnClickListener { enumerate() } })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)

        root.addView(TextView(this).apply { text = "Frame geometry (must match actual CX3 payload; no headers/line padding)" })
        widthInput = field(root, "Width", "640")
        heightInput = field(root, "Height", "480")
        formatInput = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, PixelFormat.entries.map { it.name })
        }
        root.addView(formatInput)
        root.addView(Button(this).apply { text = "Start RAW preview"; setOnClickListener { startPreview() } })
        root.addView(Button(this).apply { text = "Stop RAW preview"; setOnClickListener { camera.stop() } })

        root.addView(TextView(this).apply { text = "Vendor control transfer: values are hexadecimal. Do not guess opcodes." })
        requestType = field(root, "bmRequestType hex (40 OUT, C0 IN)", "40")
        request = field(root, "bRequest hex", "00")
        value = field(root, "wValue hex", "0000")
        index = field(root, "wIndex hex", "0000")
        payload = field(root, "OUT payload hex (e.g. 01 02)", "")
        readLength = field(root, "IN read length decimal", "0")
        root.addView(Button(this).apply { text = "Send vendor control command"; setOnClickListener { sendCommand() } })

        val filter = IntentFilter().apply {
            addAction(permissionAction); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        enumerate()
    }

    private fun field(root: LinearLayout, title: String, initial: String): EditText {
        root.addView(TextView(this).apply { text = title })
        return EditText(this).apply { setSingleLine(true); setText(initial); root.addView(this) }
    }

    private fun note(text: String) = ui.post { logView.text = text }
    private fun enumerate() {
        deviceList.removeAllViews()
        val devices = manager.deviceList.values
        if (devices.isEmpty()) note("No USB devices. Check cable and OTG.")
        devices.forEach { device ->
            val desc = buildString {
                append("VID=%04X PID=%04X".format(device.vendorId, device.productId))
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    append("\nIF ${iface.id} class=${iface.interfaceClass}")
                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        append(" EP=0x%02X/type=%d".format(ep.address, ep.type))
                    }
                }
            }
            deviceList.addView(Button(this).apply {
                text = desc + "\nConnect"
                isAllCaps = false
                setOnClickListener {
                    selected = device
                    if (manager.hasPermission(device)) connect(device)
                    else {
                        val intent = Intent(permissionAction).setPackage(packageName)
                        val pi = PendingIntent.getBroadcast(this@MainActivity, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                        manager.requestPermission(device, pi)
                    }
                }
            })
        }
    }

    private fun connect(device: UsbDevice) {
        if (streaming) { note("Stop preview before reconnecting"); return }
        runCatching { camera.open(device) }.onSuccess { note(it) }.onFailure { note("Connect failed: ${it.message}") }
    }

    private fun startPreview() {
        if (streaming) { note("Preview already running"); return }
        val width = widthInput.text.toString().toIntOrNull()
        val height = heightInput.text.toString().toIntOrNull()
        if (width == null || height == null) { note("Invalid width or height"); return }
        val format = PixelFormat.entries[formatInput.selectedItemPosition]
        if ((format == PixelFormat.RAW10 && (width.toLong() * height) % 4 != 0L) ||
            (format == PixelFormat.RAW12 && (width.toLong() * height) % 2 != 0L)) {
            note("RAW10 needs pixel count multiple of 4; RAW12 multiple of 2"); return
        }
        streaming = true
        executor.execute {
            var lastRendered = 0L
            try {
                camera.capture(width, height, format) { raw, count ->
                    val now = System.currentTimeMillis()
                    if (now - lastRendered >= 200 && previewBusy.compareAndSet(false, true)) {
                        lastRendered = now
                        try {
                            val bitmap: Bitmap = RawFrameDecoder.bitmap(raw, width, height, format)
                            ui.post {
                                image.setImageBitmap(bitmap)
                                logView.text = "Received $count fixed-size frames ($width x $height $format). No frame sync verified."
                                previewBusy.set(false)
                            }
                        } catch (e: Exception) {
                            previewBusy.set(false)
                            note("Decode failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) { note("Stream stopped: ${e.message}") }
            finally { streaming = false }
        }
    }

    private fun sendCommand() {
        if (streaming) { note("Stop preview before sending a control command"); return }
        try {
            fun hex(e: EditText): Int = e.text.toString().trim().removePrefix("0x").toInt(16)
            val raw = payload.text.toString().replace(" ", "").replace(":", "")
            require(raw.length % 2 == 0) { "Hex payload must contain full bytes" }
            val bytes = raw.chunked(2).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            val command = VendorCommand(
                hex(requestType), hex(request), hex(value), hex(index),
                bytes, readLength.text.toString().toInt()
            )
            val result = camera.control(command)
            note("Control transfer OK. IN bytes: " + result.joinToString(" ") { "%02X".format(it.toInt() and 255) })
        } catch (e: Exception) { note("Control transfer failed: ${e.message}") }
    }

    override fun onDestroy() {
        camera.stop()
        unregisterReceiver(receiver)
        executor.shutdown()
        super.onDestroy()
    }
}
