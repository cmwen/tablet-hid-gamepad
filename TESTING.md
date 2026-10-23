# Validation

Run `dart format --output=none --set-exit-if-changed .`, `flutter analyze`, `flutter test` and `flutter build apk --debug`.

Automated tests cover exact neutral/pressed report encoding, signed axes, triggers, multitouch button holds, pointer cancellation and stick cancellation. The Android build compiles FunctionFS C with `-Wall -Wextra -Werror`.

Manual hardware acceptance requires a compatible rooted tablet and Windows host. Confirm ADB survives USB activation, Windows sees gamepad usages/report length, and joy.cpl reflects sticks, triggers, D-pad and simultaneous buttons. Background while held and verify neutral. Stop and confirm original USB links/properties. Interrupt the app and verify recovery on relaunch. Bluetooth registration, pairing, report receipt and reconnect are separate checks. Steam game play remains host/game dependent. Record hardware results separately; a build or registration alone does not establish end-to-end compatibility.
