# HID and transport design

Dart owns multitouch input state and coalesces asynchronous reports through one sender. Native Android owns transports. No root operations run on Flutter's UI thread. Status is refreshed every 250 ms; input reports are event-driven.

Report ID 1 has a 11-byte body (12 bytes on USB including ID):

| Body offset | Field |
| --- | --- |
| 0–1 | 16 button bits, little endian; LT/RT are bits 11/12 (buttons 12/13) |
| 2 | Hat 0–7 clockwise from north, null 8; upper nibble padding |
| 3–10 | X, Y, Z, Rz unsigned 16-bit little endian, 0…65535; center 32768 |

Bluetooth passes ID separately to `sendReport`; USB prepends the ID. Neutral has buttons 0, hat 8, all four sticks 32768. The native FunctionFS GET_REPORT cache starts neutral before binding. Input-only descriptor: no output or feature reports and no rumble promises.

USB uses a pipe-owned root `app_process` helper and app-specific FunctionFS library, function, recovery directory and owner marker. The helper snapshots descriptors, links, temporary properties and UDC, preserves ADB, and restores on EOF. It refuses Graphics Pad/Micro recovery markers and other functions. Setup failures attempt rollback. Recovery is serialized and marked before spawning root. Invalid report sizes, queue exhaustion and write failures fail closed. The original physical VID/PID remains unchanged.

HID globals explicitly reset the hat angular unit to none and give sticks their own physical/logical range. All four axes represent sticks; digital triggers only advertise button usages, avoiding always-minimum axes in legacy games.

Bluetooth registration is confirmed only by the HID callback; bounded queues and generation checks discard stale work and neutralize peer changes. Bonded hosts may request neutral input on the HID control channel before Android reports the active interrupt connection. This does not promote that host to connected state. A connection request with no connected callback after 15 seconds gives explicit re-pair guidance. Existing asymmetric bonds cannot be repaired by changing the descriptor.

Android may unregister foreground HID when the app leaves the foreground. Native onPause and Dart lifecycle observers both neutralize input. The app keeps the screen on while visible and does not run a background service.

Report count means root-confirmed sends for USB and accepted transport sends for Bluetooth, not application-level acknowledgments from a Windows game.

Reference: [Android HID Device API](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice),
[Windows joystick capabilities](https://learn.microsoft.com/en-us/windows/win32/api/joystickapi/ns-joystickapi-joycaps),
and [USB HID specifications and usage tables](https://www.usb.org/hid).
