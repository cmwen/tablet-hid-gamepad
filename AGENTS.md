# Tablet HID Gamepad contributor guidance

This repository is an Android-only Flutter HID controller. Read README.md, docs/ARCHITECTURE.md and TESTING.md before changing transports. Workspace-managed changes must use isolated workspace Runs and reviewed integration.

Use Flutter 3.38.7, Dart 3.10.7 and Java 17. Required validation:

```sh
flutter pub get
dart format --output=none --set-exit-if-changed .
flutter analyze
flutter test
flutter build apk --debug
```

Preserve generic HID identity, report format parity, root opt-in, foreign-session rejection, ADB retention and rollback. Never represent HID registration as a successful host/game test. Add meaningful tests when changing the protocol or input handling. Do not commit signing keys, credentials, device serials or private logs. Native USB code derives from the MIT source pinned in THIRD_PARTY_NOTICES.md; preserve attribution.

Keep device debugging delegated to one owner with bounded logs and screenshots. No arbitrary root commands exposed through Flutter channels, no persistent boot scripts and no watchdog USB rewrites. Keep code formatting consistent and public documentation accurate.
