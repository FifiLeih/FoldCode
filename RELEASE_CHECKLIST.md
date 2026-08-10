# FoldCode public release checklist

## Source and build

- Confirm every intended source file is tracked and `git status --short` is clean.
- Publish only the reviewed public-release branch. Do not push unrelated local branches,
  tags, or use `--all`.
- Confirm `versionName` is `1.0.0`, `versionCode` is `1`, `minSdk` is 26, and the
  packaged ABI is only `arm64-v8a`.
- Check every local Markdown link and verify the README installation, permission,
  privacy, extension, and source-build instructions against the release candidate.
- Run `FOLDCODE_NETWORK_AUDIT=1 tools/test-core.sh`; it includes repository
  hygiene, tests, lint, both APK builds, release inspection, and extension validation.
- Package the reviewed base hosts with `tools/package-base-runtime.sh`.
- Install that bundle into a clean checkout with `tools/install-base-runtime.sh`.
- In the clean checkout, run debug/release unit tests, lint, and APK assembly.
- Run `./gradlew verifyReleaseApkContents` to confirm that ignored runtime inputs
  are absent, required base hosts are present, and the release catalog URL is empty.
- Confirm `gradle/verification-metadata.xml` and the Gradle distribution checksum
  match the reviewed dependency graph.

## Security and signing

- Run npm audit for `extensions/web/tooling` and `extensions/web/npm-patches`.
- Run the strong-pattern secret scan over the working tree and Git history.
- Inspect tracked and staged files for private keys, signing stores, credentials,
  access tokens, local paths, device data, downloaded binaries, and generated output.
- Confirm the release manifest uses the reviewed permissions only, cleartext traffic
  remains disabled, the extension catalog URL is empty, WebView debugging is disabled,
  and application backup remains disabled.
- Supply production signing values only through the four `FOLDCODE_RELEASE_*` environment variables.
- Build with `./gradlew publicRelease`; never publish the debug or unsigned APK.
- Verify the APK signature and certificate fingerprints with `apksigner`.
- Uninstall any debug-signed build, install the exact staged production APK on a clean
  ARM64 device, and confirm the expected signer remains unchanged for future updates.

## Manual acceptance test

- Start from a fresh installation, grant All files access, and confirm
  `Internal storage/FoldCode/Projects` is created when the workspace initializes.
- Install all seven approved extensions through **Extensions → From file** and confirm
  their installed state and capabilities without restarting the app.
- Test phone/folded, unfolded, and DeX layouts, including rotation policy, pointer
  scrolling, split editors, tabs, scrollbars, text selection, clipboard, and breakpoints.
- Test terminal creation, switching, selection, copy/paste, command wrapping, progress
  output, process cancellation, and independent concurrent sessions.
- Build, run, rebuild without changes, and edit/rebuild representative C, C++, Python,
  Rust, Fortran, COBOL, and Web projects.
- For Python, test project-local pip installation and import of the bundled
  `cryptography`, `cffi`, and `pycparser` Android wheels.
- For Web, test npm, TypeScript/React diagnostics, local preview, browser navigation,
  stopping/restarting the server, the right-side panel, and JavaScript debugging.
- For Pico, test RP2040 and RP2350 C/C++, Rust, and MicroPython examples; confirm
  incremental no-change builds and perform at least one BOOTSEL USB flash.
- Leave and relaunch the app after builds to check session restoration and verify there
  is no crash, orphaned build process, or unexpectedly running web server.

## GitHub release assets

- Signed FoldCode APK.
- `foldcode-cpp-1.0.0-3.fcex`
- `foldcode-python-3.14.6-6.fcex`
- `foldcode-pico-2.3.0-18.fcex`
- `foldcode-rust-1.0.0-4.fcex`
- `foldcode-gnu-arm-15.2.1-1.fcex`
- `foldcode-gnu-languages-1.0.0-6.fcex`
- `foldcode-web-1.0.0-10.fcex`
- `foldcode-base-native-arm64-v1.zip` for reproducible source builds.
- A SHA-256 checksum file covering every uploaded asset.

Do not upload old `.fcex` revisions, the obsolete Git extension, local catalogs,
`local.properties`, build directories, server directories, or signing material.

Before publishing, inspect the staging directory directly, verify every line in
`SHA256SUMS`, attach concise 1.0.0 release notes and installation requirements, and
download the published assets once to confirm their names, sizes, hashes, and signatures.
