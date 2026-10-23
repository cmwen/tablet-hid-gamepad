# Changelog

## 0.2.0

- Conventional unsigned 16-bit stick encoding (0…65535, center 32768) across Dart, Bluetooth and USB, including the initial USB GET_REPORT cache. Removed always-minimum trigger axes; digital LT/RT are independent buttons 12/13. The right stick uses Z/Rz for a conventional four-axis layout.
- Immediate neutral on USB connection/readiness; existing Bluetooth connection neutral, pointer cancellation and touch deadzone retained.
- Allow a registered Bluetooth app to answer a bonded host's initial GET_REPORT before the connected callback; explicit timeout and stale-pairing repair guidance.
- Custom launcher artwork with legacy density icons and adaptive Android masking.
- Production release signing, upgrade/migration instructions, Steam axis-mapping guidance and honest hardware acceptance records.
- Added descriptor/encoder boundary regression coverage. Windows legacy input reproduced the old trigger axes resting at minimum; actual affected-title gameplay still requires testing; a generic HID controller still requires mapping for XInput-only games.

## 0.1.0 preview

Initial Android landscape gamepad: independent multitouch controls, standard Bluetooth HID and experimental rooted FunctionFS USB with ADB preservation and recovery. Generic Windows HID/DirectInput with Steam Input setup guidance. No native XInput or rumble support. Consult hardware acceptance notes for the verified scope.
