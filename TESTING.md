# Validation

Run `dart format --output=none --set-exit-if-changed .`, `flutter analyze`, `flutter test` and `flutter build apk --debug`.

Automated tests cover exact neutral/pressed report encoding, unsigned centered axes, triggers, multitouch button holds, pointer cancellation and stick cancellation. The native descriptor is independently decoded to check axis ranges, physical units, encoded centers and total report size. The Android build compiles FunctionFS C with `-Wall -Wextra -Werror`.

Manual hardware acceptance requires a compatible rooted tablet and Windows host. Confirm ADB survives USB activation, Windows sees gamepad usages/report length, and joy.cpl reflects sticks, triggers, D-pad and simultaneous buttons. Background while held and verify neutral. Stop and confirm original USB links/properties. Interrupt the app and verify recovery on relaunch. Bluetooth registration, pairing, report receipt and reconnect are separate checks. Steam game play remains host/game dependent. Record hardware results separately; a build or registration alone does not establish end-to-end compatibility.

The local 0.2.0 release AAB was generated successfully by Gradle `bundleRelease`
and verified with `jarsigner`; its native debug symbols are in bundle metadata
rather than the delivered libraries. Flutter's final AAB postcheck reported
`Failed to find apkanalyzer` because this host has command-line tools directly in
`cmdline-tools/bin` instead of a versioned `cmdline-tools/<version>/bin` directory.
This is a host SDK layout issue; the APK build and signature validation passed.
Install Android command-line tools using the standard SDK layout before repeating
`flutter build appbundle --release`. The APK is the directly installable release;
the AAB is an app-store upload artifact.
