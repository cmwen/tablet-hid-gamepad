package com.cmwen.tablet_hid_gamepad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Foreground Classic Bluetooth HID transport for the external gamepad report. */
@SuppressLint("MissingPermission")
class BluetoothGamepadTransport(
    context: Context,
    private val handler: Handler,
    private val changed: () -> Unit,
    private val error: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "pen-bluetooth-hid").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile private var hid: BluetoothHidDevice? = null
    private var proxyPending = false
    private var registrationPending = false
    private var stopping = false
    @Volatile private var generation = 0L
    @Volatile private var peerGeneration = 0L
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var active = false
    private var closed = false
    private var lastReport = GamepadProtocol.allUp()
    private var _status = "Bluetooth HID is stopped"

    val registered: Boolean get() = active
    val busy: Boolean get() = proxyPending || registrationPending || stopping
    val connected: Boolean get() = host != null
    val status: String get() = _status
    val hostName: String? get() = host?.let { safeName(it) }

    fun start() = onMain {
        if (closed || stopping || active || registrationPending || proxyPending) return@onMain
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            fail("Classic Bluetooth HID requires Android 9 or later")
            return@onMain
        }
        if (!hasConnectPermission()) {
            fail("Bluetooth Connect permission is required")
            return@onMain
        }
        val bt = adapter
        if (bt == null) {
            fail("This device does not provide Bluetooth")
            return@onMain
        }
        if (!bt.isEnabled) {
            fail("Turn on Bluetooth to start gamepad HID")
            return@onMain
        }
        proxyPending = true
        _status = "Requesting Bluetooth HID profile"
        changed()
        val requestGeneration = generation
        val requested = try {
            bt.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    if (closed || requestGeneration != generation) {
                        bt.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                        return
                    }
                    proxyPending = false
                    hid = proxy as? BluetoothHidDevice
                    if (hid == null) {
                        fail("Android returned an invalid Bluetooth HID profile")
                        return
                    }
                    register(requestGeneration)
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (requestGeneration != generation || closed) return
                    peerGeneration++
                    worker.queue.clear()
                    proxyPending = false
                    hid = null
                    active = false
                    registrationPending = false
                    host = null
                    setLastReport(GamepadProtocol.allUp())
                    _status = "Bluetooth HID profile disconnected"
                    changed()
                }
            }, BluetoothProfile.HID_DEVICE)
        } catch (_: SecurityException) {
            false
        }
        if (!requested) {
            proxyPending = false
            fail("Android could not open the Bluetooth HID profile")
        }
    }

    fun stop() = onMain {
        if (closed || stopping) return@onMain
        if (hid == null && !active && !proxyPending && !registrationPending) return@onMain
        val oldGeneration = generation
        generation++
        peerGeneration++
        stopping = true
        registrationPending = false
        proxyPending = false
        val proxy = hid
        val oldHost = host
        host = null
        active = false
        setLastReport(GamepadProtocol.allUp())
        _status = "Stopping Bluetooth HID"
        // Discard stale reports, then put an all-up packet on the same worker before
        // disconnecting and unregistering. That keeps callbacks and sends serialized.
        worker.queue.clear()
        submitWorker {
            try {
                if (proxy != null) {
                    if (oldHost != null) safely { proxy.sendReport(oldHost, GamepadProtocol.REPORT_ID, GamepadProtocol.allUp()) }
                    if (oldHost != null) safely { proxy.disconnect(oldHost) }
                    safely { proxy.unregisterApp() }
                }
            } finally {
                handler.post {
                    if (generation != oldGeneration + 1) return@post
                    if (proxy != null) safely { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
                    if (hid === proxy) hid = null
                    stopping = false
                    _status = "Bluetooth HID is stopped"
                    changed()
                }
            }
        }
        changed()
    }

    fun close() = onMain {
        if (closed) return@onMain
        stop()
        closed = true
        // stop() queued the final report and teardown. Keep the worker alive until that
        // bounded operation completes so the neutral report is not abandoned.
        worker.shutdown()
    }

    fun send(body: ByteArray): Boolean {
        if (body.size != GamepadProtocol.BODY_SIZE) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return enqueueReport(body)
        }
        val copy = body.copyOf()
        var accepted = false
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post { accepted = enqueueReport(copy); latch.countDown() }
        return try {
            latch.await(150, TimeUnit.MILLISECONDS) && accepted
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun connect(address: String) = onMain {
        if (!active || hid == null) {
            fail("Start Bluetooth gamepad HID before connecting")
            return@onMain
        }
        if (!hasConnectPermission()) {
            fail("Bluetooth Connect permission is required")
            return@onMain
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            fail("Invalid Bluetooth host address")
            return@onMain
        }
        val device = try {
            adapter?.bondedDevices?.firstOrNull { it.address.equals(address, ignoreCase = true) }
        } catch (_: SecurityException) {
            null
        }
        if (device == null) {
            fail("Pair this host in Android Bluetooth settings first")
            return@onMain
        }
        val current = generation
        val proxy = hid ?: return@onMain
        _status = "Connecting to ${safeName(device)}"
        changed()
        submitWorker {
            val ok = safely { proxy.connect(device) } ?: false
            if (!ok) handler.post { if (current == generation) fail("Bluetooth rejected the gamepad connection request; remove pairing on both devices, then pair again while HID is ready") }
        }
        handler.postDelayed({
            if (current == generation && active && host == null) {
                fail("Bluetooth host did not connect. Remove pairing on both devices, start HID, then pair again from Windows while this app stays open")
            }
        }, 15000)
    }

    fun disconnect() = onMain {
        val device = host ?: return@onMain
        val proxy = hid ?: return@onMain
        val current = generation
        peerGeneration++
        worker.queue.clear()
        host = null
        setLastReport(GamepadProtocol.allUp())
        // The neutral report precedes the requested link teardown.
        submitWorker {
            safely { proxy.sendReport(device, GamepadProtocol.REPORT_ID, GamepadProtocol.allUp()) }
            safely { proxy.disconnect(device) }
        }
        _status = "Disconnecting from ${safeName(device)}"
        changed()
        handler.postDelayed({
            if (current == generation && host == null && active) {
                _status = "Bluetooth gamepad HID is ready"
                changed()
            }
        }, 1000)
    }

    fun pairedHosts(): List<Map<String, Any?>> {
        if (!hasConnectPermission()) return emptyList()
        return try {
            adapter?.bondedDevices.orEmpty().filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }.map { device ->
                mapOf("name" to (safeName(device) ?: "Bluetooth host"), "address" to device.address)
            }.sortedBy { it["name"].toString().lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun register(requestGeneration: Long) {
        val proxy = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Tablet HID Gamepad",
            "Standard HID gamepad",
            "Min",
            BluetoothHidDevice.SUBCLASS2_GAMEPAD,
            GamepadProtocol.activeDescriptor,
        )
        registrationPending = true
        _status = "Registering gamepad HID"
        changed()
        val callback = callbackFor(requestGeneration)
        val accepted = try {
            proxy.registerApp(sdp, null, null, { command -> handler.post(command) }, callback)
        } catch (_: SecurityException) {
            false
        }
        if (!accepted) {
            registrationPending = false
            fail("HID registration request was rejected; another HID app may be active")
            return
        }
        handler.postDelayed({
            if (requestGeneration == generation && registrationPending) {
                registrationPending = false
                fail("Bluetooth HID registration timed out")
            }
        }, 10000)
    }

    private fun callbackFor(callbackGeneration: Long) = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, accepted: Boolean) {
            if (callbackGeneration != generation || closed) return
            registrationPending = false
            active = accepted
            if (accepted) {
                _status = "Bluetooth gamepad HID is ready"
                if (pluggedDevice != null && hid?.getConnectionState(pluggedDevice) == BluetoothProfile.STATE_CONNECTED) {
                    setPeer(pluggedDevice)
                }
            } else {
                peerGeneration++
                worker.queue.clear()
                host = null
                setLastReport(GamepadProtocol.allUp())
                _status = "Bluetooth HID registration was refused"
                error(_status)
            }
            changed()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (callbackGeneration != generation || closed) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Android HID Device supports one active peer. Ignore another peer if
                    // the platform reports one while this app already owns a connection.
                    if (host == null || host?.address == device.address) {
                        setPeer(device)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    peerGeneration++
                    worker.queue.clear()
                    if (host?.address == device.address) {
                        host = null
                        setLastReport(GamepadProtocol.allUp())
                        _status = if (active) "Bluetooth gamepad HID is ready" else "Bluetooth HID is stopped"
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> _status = "Connecting to ${safeName(device)}"
                BluetoothProfile.STATE_DISCONNECTING -> _status = "Disconnecting from ${safeName(device)}"
            }
            changed()
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (callbackGeneration != generation || closed) return
            val proxy = hid ?: return
            if ((id.toInt() and 0xFF) != GamepadProtocol.REPORT_ID || type != BluetoothHidDevice.REPORT_TYPE_INPUT) {
                proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                return
            }
            // Hosts may request the initial input state on the control channel
            // before Android delivers STATE_CONNECTED. A bonded initializing
            // host must be able to read neutral without becoming the active peer.
            val currentHost = host
            if (!active || (currentHost != null && currentHost.address != device.address) ||
                device.bondState != BluetoothDevice.BOND_BONDED) {
                proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_NOT_READY)
                return
            }
            val report = synchronized(this@BluetoothGamepadTransport) { lastReport.copyOf() }
            val data = if (bufferSize in 1 until report.size) report.copyOf(bufferSize) else report
            proxy.replyReport(device, type, id, data)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (callbackGeneration != generation || closed) return
            val proxy = hid ?: return
            // This descriptor defines no Output or Feature reports.
            proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            // The report descriptor is report-protocol only. Android's API has no
            // explicit SET_IDLE callback; SET_PROTOCOL is acknowledged by the stack.
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (callbackGeneration != generation || closed) return
            val proxy = hid ?: return
            if ((reportId.toInt() and 0xFF) != GamepadProtocol.REPORT_ID || data.size > GamepadProtocol.BODY_SIZE) {
                proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (callbackGeneration != generation || closed) return
            if (host?.address != device.address) return
            peerGeneration++
            worker.queue.clear()
            host = null
            setLastReport(GamepadProtocol.allUp())
            _status = if (active) "Bluetooth gamepad HID is ready" else "Bluetooth HID is stopped"
            changed()
        }
    }

    @Synchronized
    private fun enqueueReport(body: ByteArray): Boolean {
        val target = host ?: return false
        val proxy = hid ?: return false
        if (!active || !hasConnectPermission()) return false
        val report = body.copyOf()
        val current = generation
        val currentPeer = peerGeneration
        return try {
            worker.execute {
                if (current != generation || currentPeer != peerGeneration || !active || host?.address != target.address) return@execute
                val sent = safely { proxy.sendReport(target, GamepadProtocol.REPORT_ID, report) } ?: false
                if (sent) {
                    setLastReport(report)
                } else {
                    handler.post {
                        if (current == generation && currentPeer == peerGeneration && host?.address == target.address) {
                            fail("Bluetooth HID could not queue a gamepad report")
                            disconnect()
                        }
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            handler.post {
                if (current == generation && currentPeer == peerGeneration) {
                    fail("Bluetooth gamepad report queue is full; releasing controls and disconnecting")
                    disconnect()
                }
            }
            false
        }
    }

    private fun submitWorker(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            handler.post { fail("Bluetooth HID worker is unavailable") }
        }
    }

    private fun setPeer(device: BluetoothDevice) {
        peerGeneration++
        worker.queue.clear()
        host = device
        setLastReport(GamepadProtocol.allUp())
        _status = "Connected to ${safeName(device)}"
        val proxy = hid ?: return
        val current = generation
        val currentPeer = peerGeneration
        submitWorker {
            if (current == generation && currentPeer == peerGeneration && host?.address == device.address) {
                safely { proxy.sendReport(device, GamepadProtocol.REPORT_ID, GamepadProtocol.allUp()) }
            }
        }
    }

    private fun setLastReport(report: ByteArray) {
        synchronized(this) { lastReport = report.copyOf() }
    }

    private inline fun <T> safely(action: () -> T): T? = try {
        action()
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun safeName(device: BluetoothDevice): String? = try { device.name } catch (_: SecurityException) { null }

    private fun fail(message: String) {
        _status = message
        error(message)
        changed()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post(action)
    }

}
