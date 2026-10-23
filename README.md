# Tablet HID Gamepad

A landscape, multitouch Android controller for Windows and Steam. Two sticks, a D-pad, ABXY, shoulders, independent digital triggers, stick clicks, Select, Start and Home send standard HID gamepad reports over Classic Bluetooth or rooted USB.

**Experimental preview — Android 9+; USB requires root and compatible firmware.** USB input and recovery were verified on one rooted Lenovo Android 10 tablet with a Windows host. Bluetooth registration/discoverability passed; Bluetooth pairing, Windows Bluetooth inputs and Steam gameplay remain unverified. See [hardware acceptance](docs/HARDWARE_ACCEPTANCE.md). This app presents a generic HID/DirectInput controller. It does not emulate native Xbox/XInput hardware. Steam Input can map it for compatible games. No rumble, motion sensing, battery reporting or console support is implemented.

## Install and connect

Install the APK from this repository's Releases page or build it below. Preview APKs explicitly labeled debug are signed with a development key; production release signing uses your private keystore. Android will request Bluetooth permissions on Android 12+ and your root manager will request superuser access only when USB is started.

### Bluetooth (root not required)

1. Turn on Android Bluetooth. Open **Connect → Start Bluetooth**. Allow requested permissions, then tap Start again.
2. Wait until HID is ready, tap **Make visible (120s)** and add a Bluetooth device from Windows Settings. Bluetooth HID Device profile must be supported by your Android firmware.
3. Choose the paired computer in the app if Windows does not connect automatically. The top bar reports registration, connection and accepted/confirmed reports. Remain in the foreground while playing.

Only one app can own Android's Bluetooth HID Device registration. Stop other tablet HID apps before starting this one. A successful registration is not proof that a Windows host has paired or received inputs.

### USB (root required)

1. Enable USB debugging and authorize your computer. Start from charging/ADB-only mode. Stop and restore USB in any other tablet HID app first.
2. Use a data-capable cable. Open **Connect → Enable rooted USB**, read the confirmation, and grant root in your root manager.
3. USB briefly reconnects as **Tablet HID Gamepad** plus ADB. The existing physical device VID/PID is retained; the app does not impersonate an Xbox or another vendor. Windows may reuse a prior driver association for that VID/PID.
4. Use **Stop** to release controls and restore the original USB setup. **Connect → Restore USB** retries recovery after an interruption. Relaunch also attempts recovery if a session was interrupted. If root is denied, grant it and retry Restore USB. Rebooting restores Android's normal boot-time USB configuration if the app cannot recover.

The USB backend uses FunctionFS because some tablet kernels expose `f_hid` but ignore its configurable report descriptor. It requires `/config/usb_gadget/g1`, `configs/b.1`, FunctionFS, and an already bound ADB function. Unsupported layouts, missing ADB, foreign recovery markers, and conflicting USB functions are refused. No boot script, persistent USB property, kernel patch, network listener or background service is installed. Recovery snapshots are private root-owned temporary files. ADB may drop briefly during rebinding; preserve another recovery route before experimenting. Root availability alone does not establish firmware compatibility.

## Windows and Steam

1. Press Win+R, run `joy.cpl`, select the controller and open Properties. Check button presses, stick travel, POV/D-pad and both triggers, including simultaneous touches.
2. In Steam Settings → Controller, enable Steam Input for generic controllers (wording varies by Steam version). Define or calibrate the layout if prompted. ABXY map to HID buttons 1–4; LB/RB to 5–6; Select/Start/Home to 7–9; L3/R3 to 10–11.
3. Enable Steam Input in the individual game's controller properties when necessary. Games that require XInput outside Steam may need a separate mapper; this project supplies no Windows driver or mapper.

The touchscreen triggers currently send released/fully pressed values, not pressure. The D-pad uses an eight-way HID hat. Pointer cancellation, connection transitions, app backgrounding and Stop release controls. USB helper EOF also sends neutral and restores the original gadget. After an abrupt power loss or root failure, recovery may require relaunch or reboot.

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
