# Tablet HID Gamepad

<img src="assets/app-icon.png" width="128" alt="Tablet HID Gamepad icon">

A landscape, multitouch Android controller for Windows and Steam. Two sticks, a D-pad, ABXY, shoulders, independent trigger buttons, stick clicks, Select, Start and Home send standard HID gamepad reports over Classic Bluetooth or rooted USB.

**Version 0.2.0 — experimental; Android 9+; USB requires root and compatible firmware.** The production-signed APK passed Windows USB input tests on one rooted Lenovo Android 10 tablet, including neutral before touch and all advertised legacy axes centered at idle. Bluetooth registration/discoverability passed; Windows Bluetooth inputs, reconnect and Steam gameplay remain unverified. See [hardware acceptance](docs/HARDWARE_ACCEPTANCE.md). This app presents a generic HID/DirectInput controller. It does not emulate native Xbox/XInput hardware. Steam Input can map it for compatible games. No rumble, motion sensing, battery reporting or console support is implemented.

## Install and connect

Install the [signed 0.2.0 APK](https://github.com/cmwen/tablet-hid-gamepad/releases/download/0.2.0/tablet-hid-gamepad-0.2.0.apk) from [Releases](https://github.com/cmwen/tablet-hid-gamepad/releases/tag/0.2.0), or build it below. The published APK is ready to install and requires no signing setup. Developers building their own release need a private keystore; debug builds use a development key. Android will request Bluetooth permissions on Android 12+ and your root manager will request superuser access only when USB is started.

### Bluetooth (root not required)

1. Turn on Android Bluetooth. Open **Connect → Start Bluetooth**. Allow requested permissions, then tap Start again.
2. Wait until HID is ready, tap **Make visible (120s)** and add a Bluetooth device from Windows Settings. Windows discovers the tablet by its current Android Bluetooth name, which may differ from **Tablet HID Gamepad**. You can rename it in Android Bluetooth settings. Bluetooth HID Device profile must be supported by your Android firmware.
3. Choose the paired computer in the app if Windows does not connect automatically. Pairing and an active HID connection are separate states: **HID ready** only means Android registered the app; **connected** means Android reports a host link. Confirm actual inputs in `joy.cpl`. Remain in the foreground while playing.
4. If paired but absent from `joy.cpl`, remove the tablet from Windows Bluetooth devices and forget the computer in Android Bluetooth settings. Start Bluetooth in this app again, wait for HID ready, make visible, and add the tablet from Windows. Accept matching pairing prompts. Pair while this app is visible so Windows discovers its gamepad service. Existing bonds from a different tablet HID app or a one-sided remembered pairing can prevent connection. There is no need to remove your keyboard or other unrelated devices.

Only one app can own Android's Bluetooth HID Device registration. Stop other tablet HID apps before starting this one. A successful registration is not proof that a Windows host has paired or received inputs.

### USB (root required)

1. Enable USB debugging and authorize your computer. Start from charging/ADB-only mode. Stop and restore USB in any other tablet HID app first.
2. Use a data-capable cable. Open **Connect → Enable rooted USB**, read the confirmation, and grant root in your root manager.
3. USB briefly reconnects as **Tablet HID Gamepad** plus ADB. The existing physical device VID/PID is retained; the app does not impersonate an Xbox or another vendor. Windows may reuse a prior driver association for that VID/PID.
4. Use **Stop** to release controls and restore the original USB setup. **Connect → Restore USB** retries recovery after an interruption. Relaunch also attempts recovery if a session was interrupted. If root is denied, grant it and retry Restore USB. Rebooting restores Android's normal boot-time USB configuration if the app cannot recover.

The USB backend uses FunctionFS because some tablet kernels expose `f_hid` but ignore its configurable report descriptor. It requires `/config/usb_gadget/g1`, `configs/b.1`, FunctionFS, and an already bound ADB function. Unsupported layouts, missing ADB, foreign recovery markers, and conflicting USB functions are refused. No boot script, persistent USB property, kernel patch, network listener or background service is installed. Recovery snapshots are private root-owned temporary files. ADB may drop briefly during rebinding; preserve another recovery route before experimenting. Root availability alone does not establish firmware compatibility.

## Windows and Steam

1. Press Win+R, run `joy.cpl`, select the controller and open Properties. Check button presses, stick travel, POV/D-pad and both triggers, including simultaneous touches.
2. In Steam Settings → Controller, enable Steam Input for generic controllers (wording varies by Steam version). Define or calibrate the layout if prompted. ABXY map to HID buttons 1–4; LB/RB to 5–6; Select/Start/Home to 7–9; L3/R3 to 10–11; LT/RT to 12–13.
3. Enable Steam Input in the individual game's controller properties when necessary. Games that require XInput outside Steam may need a separate mapper; this project supplies no Windows driver or mapper.

LT/RT send independent digital buttons 12/13; no trigger axes or pressure are advertised. The D-pad uses an eight-way HID hat. Pointer cancellation, connection transitions, app backgrounding and Stop release controls. USB helper EOF also sends neutral and restores the original gadget. After an abrupt power loss or root failure, recovery may require relaunch or reboot.

## If a game moves while untouched

Stop the controller, release all fingers, reconnect, and inspect the matching controller in `joy.cpl` before opening the game. Sticks should stay at center, POV should be released, and every button should be up. Version 0.2.0 uses conventional unsigned 16-bit stick centers (32768), sends neutral on connection, and retains an 8% touch deadzone. A centered raw HID report does not prove that an individual game mapped the axes correctly.

In Steam's controller layout/calibration, map **X/Y → left stick**, **Z/Rz → right stick**, **buttons 12/13 → left/right trigger**, and the hat to D-pad. All four axes now center halfway through their range. Version 0.1.0 advertised extra trigger axes resting at minimum; legacy games could treat them as sticks held fully left/down. Version 0.2.0 removes those axes and uses trigger buttons instead. Clear old bindings and redefine both sticks and triggers after updating. Enable Steam Input for that game and test its layout preview. Avoid simultaneous mappings from Steam Input and a second mapper. Games using only native XInput require a compatible mapping layer; this app remains generic HID.

After updating the report descriptor, stop and reconnect USB. For Bluetooth, remove the old pairing on both ends and pair again with HID ready to refresh the cached service/report information. If Windows retained old calibration, reset the matching controller's calibration in `joy.cpl`, then calibrate only if the live axes need it. Record the game, transport, Windows input API, and whether `joy.cpl` itself moves at idle when reporting a problem.

## Updating from 0.1.0 debug preview

The production release uses a dedicated private signing identity. Android cannot install it over an APK signed by a different debug key. First stop/restore USB and stop Bluetooth. If Android reports a signing conflict, uninstall the old preview manually, then install the release APK; uninstalling removes app settings and root permissions may need granting again. Do not uninstall while rooted USB is active. Future releases retain this production identity and can update normally. See [signing](SIGNING.md).

## Build and validate

Use Flutter 3.38.7 / Dart 3.10.7, Java 17, Android SDK and NDK/CMake 3.22.1. Package: `com.cmwen.tablet_hid_gamepad`.

```sh
flutter pub get
dart format --output=none --set-exit-if-changed .
flutter analyze
flutter test
flutter build apk --debug
```

APK: `build/app/outputs/flutter-apk/app-debug.apk`. Release builds require a private signing configuration; see [SIGNING.md](SIGNING.md). GitHub workflows validate debug builds and create signed releases only when repository secrets are configured. No signing credentials are included.

See [observed hardware acceptance](docs/HARDWARE_ACCEPTANCE.md), [protocol and safety design](docs/ARCHITECTURE.md), [testing](TESTING.md) and [third-party notices](THIRD_PARTY_NOTICES.md). Licensed under [MIT](LICENSE). Built from [min-android-app-template](https://github.com/cmwen/min-android-app-template).
