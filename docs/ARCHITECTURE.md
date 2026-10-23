# HID and transport design

Dart owns multitouch input state and coalesces asynchronous reports through one sender. Native Android owns transports. No root operations run on Flutter's UI thread. Status is refreshed every 250 ms; input reports are event-driven.

Report ID 1 has a 13-byte body (14 bytes on USB including ID):

| Body offset | Field |
| --- | --- |
| 0–1 | 16 button bits, little endian |
| 2 | Hat 0–7 clockwise from north, null 8; upper nibble padding |
| 3–10 | X, Y, Rx, Ry signed 16-bit little endian, −32767…32767 |
| 11–12 | Z/Rz unsigned triggers, 0…255 |

Bluetooth passes ID separately to `sendReport`; USB prepends the ID. Neutral has only hat byte 8. The native FunctionFS GET_REPORT cache starts neutral before binding. Input-only descriptor: no output or feature reports and no rumble promises.

USB uses a pipe-owned root `app_process` helper and app-specific FunctionFS library, function, recovery directory and owner marker. The helper snapshots descriptors, links, temporary properties and UDC, preserves ADB, and restores on EOF. It refuses Graphics Pad/Micro recovery markers and other functions. Setup failures attempt rollback. Recovery is serialized and marked before spawning root. Invalid report sizes, queue exhaustion and write failures fail closed. The original physical VID/PID remains unchanged.

Bluetooth registration is confirmed only by the HID callback; bounded queues and generation checks discard stale work and neutralize peer changes. Android may unregister foreground HID when the app leaves the foreground. Native onPause and Dart lifecycle observers both neutralize input. The app keeps the screen on while visible and does not run a background service.

Report count means root-confirmed sends for USB and accepted transport sends for Bluetooth, not application-level acknowledgments from a Windows game.
