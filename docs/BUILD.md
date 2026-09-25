# Source-only builds

Public APK distribution is suspended while accessory authentication is reviewed.
The recovered identity must not be extracted from old releases, imported or bundled again.
Local compilation does not make a distributed private key confidential.

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358 and the included Gradle wrapper.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

This produces a development APK without accessory identity assets. The current product bootstrap requires authentication provisioning, so this build does not provide a working standalone CarPlay connection. It is for source validation only.

The automatic `.private/assets` import has been removed. The mobile pre-build check rejects credential files in APK asset source directories. The retired `scripts/build-beta.py` exits without building. Tests generate fresh self-signed synthetic identities at runtime; no static private key is stored in this repository.

A future distributable build needs an authorized authentication/provisioning design. The separate Android release-signing key remains local and must never be committed. See SECURITY.md.
