# Hardware acceptance — 0.2.0

Validation date: 2026-10-23. The production-signed 0.2.0 APK was installed and tested on the same rooted Lenovo Android 10 tablet and Windows host as the earlier preview. Hardware and game compatibility remain specific to the tested setup.

| Check | Observed 0.2.0 result |
| --- | --- |
| Rooted USB / ADB | Activated and restored to original configuration; ADB returned |
| Windows report capabilities | Gamepad collection; report ID 1; 12 bytes including ID; 16 button bits; null-capable hat; four unsigned 16-bit axes X/Y/Z/Rz, 0…65535 |
| Live Windows USB input | 556 actual reports from the signed APK; initial raw report neutral before touch; right-stick Z/R movement and LT/RT masks 2048/4096 verified. The preceding debug test verified travel on all four stick axes |
| Final raw HID neutral | Buttons 0; hat 8; all four sticks 32768 |
| Continuous Windows legacy polling | Capabilities report four axes (flags 0x33). Supported X/Y/Z/R axes centered at 32767 initially, during delayed idle and after release; POV 65535 and buttons 0. Unsupported U/V fields were 0 and are not advertised controller axes |
| Original bug diagnosis | Continuous polling on 0.1.0 showed independent trigger axes at minimum 0 while untouched. Version 0.2.0 replaces these axes with digital buttons and centers every advertised axis |
| Bluetooth failure diagnosis | A tablet-side remembered host had no matching authenticated tablet entry on Windows: asymmetric stale pairing. Registration alone did not establish connection |
| Automated checks | Formatter and analyzer clean; five encoder/descriptor/multitouch tests passed; debug APK built |
| Production signing | Dedicated release key; APK signature verified; installed and tested; app data preserved during the explicit one-time debug-to-production migration and app root permission renewed |
| Bluetooth retest | Callback-confirmed HID ready and discoverability; ordinary pairing could not complete while the Windows session was locked. Live Windows Bluetooth inputs and reconnect remain unverified |
| Steam / affected game | Actual affected-title gameplay remains unverified; old mappings must be recreated for the new descriptor |

Tested production APK SHA-256: `95edf4fd2418cdf10845954fe0c11a344a1d62379cc9e3f07dead42eaef9ec53`.

The old digital touchscreen triggers already had no pressure sensing; version 0.2.0 exposes their true button behavior. Generic HID still does not provide native XInput or rumble. A fresh Bluetooth pairing is required after changing the report descriptor. Existing Android Bluetooth device names may come from another HID app and are separate from this app's SDP gamepad name.

## Historical 0.1.0 preview

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
