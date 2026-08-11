# Contributing to FoldCode

Thank you for helping improve FoldCode. Keep changes focused, preserve the offline-first
design, and avoid committing generated runtimes, build output, credentials, device data,
or local configuration.

## Development requirements

- JDK 17
- Android SDK 36
- Android NDK 27.2.12479018
- The Gradle wrapper included in the repository
- An ARM64 Android device or emulator for device testing

The APK-owned ARM64 command hosts are deliberately not committed as opaque binaries.
Install the reviewed contributor bundle published with the latest release:

```bash
tools/install-base-runtime.sh /path/to/foldcode-base-native-arm64-v1.zip
```

You can then build and install a debug APK with:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, set `foldcodeBaseNativeDir` in ignored `local.properties` to an
equivalent reviewed runtime directory.

## Automated checks

Run the source-only suite when the contributor runtime bundle is unavailable:

```bash
tools/test-core.sh --quick
```

Before submitting a release-affecting change, run the complete non-UI suite:

```bash
FOLDCODE_NETWORK_AUDIT=1 tools/test-core.sh
```

The complete suite checks repository hygiene and legal metadata, JVM tests, Android
lint for debug and release, both APK builds, release APK contents, npm advisories, and
all seven approved extension packages when present.

GitHub runs the source-only checks for pushes and pull requests through
`.github/workflows/core-checks.yml`. Third-party workflow actions are pinned to reviewed
immutable commits, and Gradle dependencies are checked against
`gradle/verification-metadata.xml`.

## Pico SDK compilation matrix

The optional host-side Pico matrix requires five external tool paths:

```bash
export PICO_TEST_SDK=/path/to/pico-sdk
export PICO_TEST_TOOLCHAIN=/path/to/arm-none-eabi-toolchain
export PICO_TEST_CMAKE=/path/to/cmake
export PICO_TEST_NINJA=/path/to/ninja
export PICO_TEST_PICOTOOL=/path/to/picotool
tools/test-core.sh --require-pico
```

These paths are local inputs and must not be committed.

## Pull requests

- Explain the user-visible behavior and the devices or layouts affected.
- Add or update automated coverage for non-UI behavior when practical.
- Run `tools/test-core.sh --quick` and the relevant device tests.
- Do not weaken dependency verification, release signing, extension validation, or
  repository-hygiene checks to make a build pass.
- Keep signing material, tokens, passwords, private keys, and local paths out of commits.

The Android source ownership map is documented in
[`app/src/main/java/dev/foldcode/ide/ARCHITECTURE.md`](app/src/main/java/dev/foldcode/ide/ARCHITECTURE.md).
Maintainers should use [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) for signed releases.
