# Hardware acceptance — 0.1.0 preview

Validation date: 2026-10-23. This records observed results for one compatible rooted tablet and Windows host; it is not a promise of compatibility with every Android firmware or game.

| Check | Observed result |
| --- | --- |
| Android target | Lenovo Tab M8 TB-8505F, Android 10 / API 29, Magisk root |
| Controller launch | Landscape Flutter app installed and launched |
| Rooted USB activation | FunctionFS composite initialized; ADB returned after USB rebinding |
| Windows enumeration | One matching Generic Desktop / Gamepad HID collection (usage page 1, usage 5) |
| Windows input capabilities | Report ID 1; 14 bytes including ID; 16 buttons; null-capable 4-bit hat 0–7; four signed 16-bit axes −32767…32767; two unsigned 8-bit triggers 0…255 |
| Output / feature reports | None declared |
| Live Windows USB input | 316 reports read from the host; A/B/LB press and release (bitmasks 1/2/16), D-pad up/right/neutral, all four stick axes changing, and both triggers 0/255 |
| Input release after interaction | Final host-read report fully neutral: buttons 0, hat 8, sticks 0, triggers 0 |
| Background while held | A remained physically held; Windows received button 1 followed by neutral before touch release |
| Top-bar Stop | While USB was active, released inputs and restored original ADB-only USB mode |
| Manual Restore USB | Original `adb` USB configuration/state and only the ADB function/mount restored; Windows gamepad match count returned to 0 |
| Force-stop while USB active | Root helper EOF restored the original `adb` configuration/state; no gamepad function/mount remained; Windows gamepad match count returned to 0 |
| Bluetooth registration | Callback-confirmed “Bluetooth gamepad HID is ready” |
| Bluetooth discoverability | Accepted 120-second visibility request; Android reported connectable/discoverable scan mode |
| Final cleanup | ADB-only links/mount/configuration/state restored; Bluetooth stopped; no recovery snapshot, gamepad function or gamepad mount remained (only empty session lock) |
| Automated validation | Analyzer clean, three encoder/input tests passed, debug APK built; formatter and diff whitespace checks passed |

Both **Connect → Restore USB** and the top-bar **Stop** action were tested. Abrupt power loss, unavailable root during recovery, and other firmware layouts remain outside this test.

Bluetooth registration and discoverability passed; no Bluetooth pairing was attempted and no new bond events occurred. Pairing, reconnect and end-to-end Windows Bluetooth input remain unverified. Windows USB enumeration alone does not establish Steam Input mapping or successful play in an individual game. Steam in-game behavior and wider device compatibility remain unverified.

Tested APK SHA-256: `8112da3c400677fdeec0fdf169c6b2c567944369764fb2b203dd3939c4778836`.

The test APK is a development-signed preview. Production distribution requires a private signing key. No device serials, host identifiers or credentials are published here.
