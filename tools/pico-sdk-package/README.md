# FoldCode Pico SDK bundles

FoldCode has one application-wide Pico SDK release, declared in
`PicoSdkRelease.kt`. Five replaceable bundles provide RP2040, RP2350 ARM,
Pico W, Pico 2 W, and RP2350 RISC-V. They contain SDK headers, linker fragments,
GNU runtime libraries, and SDK components as individual static archives. Project
`target_link_libraries(...)` declarations are validated by FoldCode's dependency
resolver and drive USB/UART stdio configuration.

Regenerate every platform asset with:

```sh
SDK_VERSION=2.3.0 \
GCC_VERSION=15.2.1 \
PICO_SDK_ROOT="$HOME/.pico-sdk/sdk/2.3.0" \
PICO_TOOLCHAIN_ROOT="$HOME/.pico-sdk/toolchain/15_2_Rel1" \
PICO_RISCV_TOOLCHAIN_ROOT="$HOME/.pico-sdk/toolchain/riscv-15_2" \
PICOTOOL_ROOT="$HOME/.pico-sdk/picotool/2.3.0" \
./tools/update-pico-sdk-bundles.sh
```

Use `BUNDLES=rp2040,pico_w` (or another comma-separated subset) while developing
one profile. Omitting it regenerates all five production bundles.

To update the SDK later:

1. Install the matching SDK, GNU Arm and GNU RISC-V toolchains, and picotool release.
2. Change the versions and bundle revision in `PicoSdkRelease.kt`.
3. Run `tools/update-pico-sdk-bundles.sh` with the new paths.
4. Update the payload hashes/version in `extensions/pico/manifest.json`, then run
   `tools/build-pico-extension.sh` to create the distributable `.fcex` file.
5. Build one project for every board profile, inspect it with official picotool,
   and test it on physical hardware before release.

To add another Raspberry Pi silicon family or board, add its description in
`PicoProject.kt`, extend chip-specific compiler/UF2 rules if necessary, and add
one `package_target` line to the update script. It still uses the same SDK release.

The official SDK's `pioasm` sources are compiled by Android's NDK as
`foldpioasm.so`, so `.pio` sources are converted to headers locally on the phone.
This small executable remains in the extension host because Android does not allow
executing downloaded binaries from writable app storage; the large SDK/toolchain
payloads live exclusively in the installable extension.
