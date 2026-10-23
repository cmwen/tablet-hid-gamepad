package com.cmwen.tablet_hid_gamepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bluetooth: BluetoothGamepadTransport
    private lateinit var usb: UsbHidTransport
    private var mode = "bluetooth"
    private var error: String? = null
    private var sent = 0L
    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bluetooth = BluetoothGamepadTransport(this, handler, {}, { error = it })
        usb = UsbHidTransport(this, handler, object : UsbHidTransport.Listener {
            override fun onRegistered(registered: Boolean) {}
            override fun onConnection(connected: Boolean) {}
            override fun onReport(data: ByteArray) {}
            override fun onError(message: String) { error = message }
            override fun onSent() { sent++ }
            override fun onChanged() {}
        })
        usb.recoverIfNeeded()
        MethodChannel(engine.dartExecutor.binaryMessenger, "tablet_hid_gamepad/hid").setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "status" -> result.success(status())
                    "startBluetooth" -> { neutral(); usb.stop(); mode = "bluetooth"; error = null
                        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 10)
                            error = "Grant Bluetooth permission, then tap Start Bluetooth again"
                        } else bluetooth.start()
                        result.success(null)
                    }
                    "discoverable" -> {
                        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), 11)
                            error = "Grant Nearby devices permission, then tap Make visible again"
                        } else startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                        result.success(null)
                    }
                    "connect" -> { bluetooth.connect(call.argument<String>("address") ?: ""); result.success(null) }
                    "startUsb" -> { neutral(); bluetooth.stop(); mode = "usb"; error = null; usb.start(); result.success(null) }
                    "stop" -> { neutral(); bluetooth.stop(); usb.stop(); result.success(null) }
                    "restoreUsb" -> { neutral(); usb.recover(); result.success(null) }
                    "report" -> {
                        val report = call.argument<ByteArray>("body")
                        require(report?.size == GamepadProtocol.BODY_SIZE) { "Invalid report length" }
                        val ok = if (mode == "usb") usb.send(report!!) else bluetooth.send(report!!)
                        if (ok && mode == "bluetooth") sent++
                        result.success(ok)
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) { error = e.message; result.error("hid", e.message, null) }
        }
    }
    private fun status(): Map<String, Any?> = mapOf(
        "mode" to mode, "registered" to (if (mode == "usb") usb.active else bluetooth.registered),
        "connected" to (if (mode == "usb") usb.connected else bluetooth.connected),
        "busy" to (if (mode == "usb") usb.busy else bluetooth.busy),
        "message" to (if (mode == "usb") usb.status else bluetooth.status),
        "error" to error, "sent" to sent, "hosts" to bluetooth.pairedHosts(),
        "host" to bluetooth.hostName,
    )
    private fun neutral() { if (::bluetooth.isInitialized) bluetooth.send(GamepadProtocol.allUp()); if (::usb.isInitialized) usb.send(GamepadProtocol.allUp()) }
    override fun onPause() { neutral(); super.onPause() }
    override fun onDestroy() { neutral(); if (::bluetooth.isInitialized) bluetooth.close(); if (::usb.isInitialized) usb.stop(); super.onDestroy() }
}
