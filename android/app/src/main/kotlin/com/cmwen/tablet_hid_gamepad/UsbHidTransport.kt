package com.cmwen.tablet_hid_gamepad

import android.content.Context
import android.os.Handler
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns one root helper over private process pipes; no exported command endpoint. */
class UsbHidTransport(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener
) {
    interface Listener {
        fun onRegistered(registered: Boolean)
        fun onConnection(connected: Boolean)
        fun onReport(data: ByteArray)
        fun onError(message: String)
        fun onSent() {}
        fun onChanged()
    }
    companion object {
        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        fun rootCommand(context: Context, args: List<String>): Process =
            rootClassCommand(context, "com.cmwen.tablet_hid_gamepad.UsbRootMain", args)
        fun rootClassCommand(context: Context, className: String, args: List<String>): Process {
            require(className.matches(Regex("com\\.cmwen\\.tablet_hid_gamepad\\.[A-Za-z0-9_]+")))
            val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                className + args.joinToString(separator = "", transform = { " " + quote(it) })
            return ProcessBuilder("su", "-c", command).start()
        }
    }
    private val operations = Executors.newSingleThreadExecutor { task -> Thread(task, "pad-usb-operations").apply { isDaemon = true } }
    @Volatile private var generation = 0L
    @Volatile private var process: Process? = null
    @Volatile private var writer: Thread? = null
    private var queue: ArrayBlockingQueue<String>? = null
    private var recovering = false
    private val recoveryCallbacks = mutableListOf<(Boolean) -> Unit>()
    var busy = false; private set
    var active = false; private set
    var connected = false; private set
    var status = "USB inactive"; private set

    private fun ui(token: Long, block: () -> Unit) { handler.post { if (generation == token) block() } }
    private fun inactive() { active = false; connected = false; listener.onRegistered(false); listener.onConnection(false); listener.onChanged() }
    private fun failed(message: String) { busy = false; status = message; inactive(); listener.onError(message) }
    private fun rememberRecovery(needed: Boolean) {
        context.getSharedPreferences("hid", Context.MODE_PRIVATE).edit().putBoolean("usbRecoveryNeeded", needed).commit()
    }

    fun start() {
        if (busy) return
        if (active) {
            // A cable detach can close ADB FunctionFS and unbind the composite.
            // An explicit Start restarts it; no background watchdog is needed.
            if (!connected) recover { restored -> if (restored) start() }
            return
        }
        val token = ++generation
        busy = true
        status = "Starting rooted USB mode"; listener.onChanged()
        // Written before spawning root or changing the gadget, so process death
        // cannot erase the app's knowledge that recovery may be needed.
        rememberRecovery(true)
        handler.postDelayed({
            if (token == generation && busy && !active) {
                status = "USB start timed out. Grant app root in Magisk, then restore USB."
                listener.onError(status)
                recover()
            }
        }, 25000)
        operations.execute {
            try {
                stopProcess()
                if (token != generation) return@execute
                val child = rootCommand(context, listOf("run", java.io.File(context.applicationInfo.nativeLibraryDir, "libgamepad_usb_hid.so").path, Base64.encodeToString(GamepadProtocol.activeDescriptor, Base64.NO_WRAP)))
                process = child
                val outgoing = ArrayBlockingQueue<String>(128)
                queue = outgoing
                drainErrors(child)
                writer = Thread({
                    try {
                        val pipe = child.outputStream.bufferedWriter()
                        while (generation == token) {
                            val line = outgoing.take()
                            pipe.write(line); pipe.newLine(); pipe.flush()
                        }
                    } catch (_: InterruptedException) { }
                    catch (e: Exception) { ui(token) { failed("USB report pipe failed: ${e.message}") } }
                }, "pad-usb-writer").apply { isDaemon = true; start() }
                Thread({ readSession(child, token) }, "pad-usb-reader").apply { isDaemon = true; start() }
            } catch (e: Exception) { ui(token) { failed("Cannot start rooted USB mode: ${e.message}") } }
        }
    }

    private fun readSession(child: Process, token: Long) {
        var clean = false
        var explicitError = false
        var becameReady = false
        try {
            child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.length > 8192) error("Oversized USB helper response")
                val event = JSONObject(line)
                when (event.optString("event")) {
                    "ready" -> { becameReady = true; ui(token) { busy = false; active = true; status = "USB HID + ADB enabled"; listener.onRegistered(true); if (connected) send(GamepadProtocol.allUp()); listener.onChanged() } }
                    "connection" -> ui(token) { connected = event.optBoolean("connected"); if (connected) send(GamepadProtocol.allUp()); listener.onConnection(connected); listener.onChanged() }
                    "report" -> {
                        val data = Base64.decode(event.getString("data"), Base64.DEFAULT)
                        if (data.size in 2..GamepadProtocol.BODY_SIZE) ui(token) { listener.onReport(data) }
                    }
                    "error" -> { explicitError = true; ui(token) {
                        if (event.has("recoveryPending") && !event.getBoolean("recoveryPending")) rememberRecovery(false)
                        failed(event.optString("message", "Root USB operation failed"))
                    } }
                    "sent" -> ui(token) { listener.onSent() }
                    "closed" -> { clean = true; ui(token) { busy = false; rememberRecovery(false); status = "Original USB mode restored"; inactive() } }
                }
            } }
            if (!clean && !explicitError) ui(token) { failed(if (becameReady)
                "USB helper stopped; restore Android USB / ADB"
                else "Root helper did not start. Grant Tablet HID Gamepad superuser access in Magisk, then restore USB and retry.") }
        } catch (e: Exception) { ui(token) { failed("USB helper connection ended: ${e.message}. Restore USB manually.") } }
    }

    fun send(body: ByteArray): Boolean {
        if (!active || !connected || body.size != GamepadProtocol.BODY_SIZE) return false
        val accepted = queue?.offer(JSONObject().put("body", Base64.encodeToString(body, Base64.NO_WRAP)).toString()) == true
        if (!accepted) {
            status = "USB pen queue full; restoring USB to release contact"
            listener.onError(status)
            recover()
        }
        return accepted
    }

    fun stop() {
        if (!active && process == null &&
            !context.getSharedPreferences("hid", Context.MODE_PRIVATE).getBoolean("usbRecoveryNeeded", false)) return
        recover()
    }
    fun recoverIfNeeded(onDone: (Boolean) -> Unit = {}) {
        if (!context.getSharedPreferences("hid", Context.MODE_PRIVATE).getBoolean("usbRecoveryNeeded", false)) { onDone(true); return }
        recover(onDone)
    }
    fun recover(onDone: (Boolean) -> Unit = {}) {
        recoveryCallbacks.add(onDone)
        if (recovering) return
        recovering = true
        val token = ++generation
        busy = true
        status = "Restoring original USB mode"; inactive()
        operations.execute {
            val success = try {
                stopProcess()
                val child = rootCommand(context, listOf("recover"))
                child.outputStream.close(); drainErrors(child)
                check(child.waitFor(12, TimeUnit.SECONDS)) { child.destroyForcibly(); "USB recovery timed out" }
                val events = child.inputStream.bufferedReader().readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                val error = events.firstOrNull { it.optString("event") == "error" }
                check(error == null && child.exitValue() == 0 && events.any { it.optString("event") == "restored" }) {
                    error?.optString("message") ?: "Root did not confirm USB recovery"
                }
                rememberRecovery(false)
                true
            } catch (e: Exception) {
                ui(token) { failed("USB restore failed: ${e.message}") }
                false
            }
            ui(token) {
                busy = false
                if (success) { status = "Original USB mode restored"; inactive() }
                recovering = false
                val callbacks = recoveryCallbacks.toList()
                recoveryCallbacks.clear()
                callbacks.forEach { it(success) }
            }
        }
    }
    private fun stopProcess() {
        writer?.interrupt(); writer = null; queue = null
        val child = process ?: return
        process = null
        // EOF is the normal session boundary. A separate recover invocation
        // handles hard-killed helpers which cannot run their finally block.
        runCatching { child.outputStream.close() }
        if (!child.waitFor(3, TimeUnit.SECONDS)) {
            child.destroy()
            if (!child.waitFor(1, TimeUnit.SECONDS)) child.destroyForcibly()
        }
    }
    private fun drainErrors(child: Process) {
        Thread({ runCatching { child.errorStream.bufferedReader().useLines { lines -> lines.forEach { /* never echo root command lines */ } } } },
            "pad-usb-root-stderr").apply { isDaemon = true; start() }
    }
}
