package com.cmwen.tablet_hid_gamepad

import androidx.annotation.Keep
import java.io.File

/** Root-process-only FunctionFS HID, bypassing Lenovo's hardcoded f_hid. */
@Keep
class UsbFunctionFs(library: File, private val received: (ByteArray) -> Unit) {
    init {
        require(library.name == "libgamepad_usb_hid.so" && library.isFile)
        System.load(library.canonicalPath)
    }
    external fun start(path: String, reportDescriptor: ByteArray, reportId: Int, reportSize: Int)
    external fun send(report: ByteArray): Boolean
    external fun stop()
    @Keep fun onReport(report: ByteArray) = received(report)
}
