# Android release signing

Debug builds work without release credentials:

```bash
flutter build apk --debug
```

Release APKs and App Bundles require a private signing key. Release builds fail
if signing configuration is absent or incomplete; there is no debug-key fallback.
Both release workflows require all four secrets before building. They never generate
keys, expose passwords as outputs, or upload signing files as artifacts.

## Generate and back up a private key

Install JDK 17, then run this helper in a private interactive terminal:

```bash
./scripts/signing/generate-keystore.sh
```

Choose a new absolute path outside the repository, in an existing private directory.
The helper refuses to overwrite files. Java keytool prompts for passwords and
certificate details directly; passwords do not appear in command arguments. It
creates a JKS keystore with permissions restricted to its owner, without writing
password files or uploading anything.

Keep an encrypted backup of the keystore and store its passwords in a password
manager. Retain the same signing identity for application updates. With Play App
Signing, distinguish your upload key from Google's app signing key and follow
Google Play's recovery process if an upload key is lost. Repository secrets are
build inputs, not a downloadable backup.

## Local release builds

Create the ignored `android/key.properties` privately with permissions `600`:

```properties
storeFile=/absolute/private/path/my-app.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=release
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` may be absolute (recommended); relative paths resolve from
`android/app`. Use Java properties escaping for backslashes, leading spaces, and
non-ASCII characters. Keep the file out of source control and shared logs.

```bash
flutter build apk --release
flutter build appbundle --release
```

Gradle verifies all four local properties and that the keystore exists. Android's
signing task then validates the actual alias and passwords.

## GitHub release workflows

In **Settings → Secrets and variables → Actions**, configure all four repository
secrets. Use environment secrets only if you also configure the workflow's environment.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of your existing private keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias selected during key generation |
| `ANDROID_KEY_PASSWORD` | Key password (explicitly set even if it equals the store password) |

Encode privately without printing the result to a terminal or CI log. For example,
inside a private directory outside the repository:

```bash
umask 077
base64 < /absolute/private/path/my-app.jks > keystore.base64
```

Copy the encoded content into the secret field using a private editor, then remove
the temporary base64 file. Base64 is encoding, not encryption. Do not commit it,
attach it to a release, or upload it as an artifact.

The signing action validates all four inputs, decodes the keystore into a private
runner temporary directory, and creates an escaped `android/key.properties` for
Gradle. The workflows remove those files in an `always()` cleanup step. Only APK
and AAB files are published. No credential download or persistence step is needed.

## Migrating from the 0.1.0 development preview

The official 0.2.0 APK uses a dedicated production key. A debug-signed preview has a different certificate, so Android correctly rejects an in-place update. Stop and restore rooted USB, stop Bluetooth, then uninstall the preview manually before installing the release. App preferences and app-scoped root permissions may be removed. No automated release or install script uninstalls an existing application. Future production releases must use the same key.

## Troubleshooting

- **Missing signing configuration:** check that every property or secret listed
  above has a nonempty value. Debug builds remain available without release setup.
- **Keystore not found:** verify the absolute `storeFile` path and file permissions.
- **Invalid keystore or password:** confirm the original keystore, its alias, and
  both passwords privately. Re-encode the original file for CI if needed.
- **Updates rejected:** confirm the application ID and signing certificate match
  the previously distributed app. Do not replace an established release key with a
  newly generated key.
