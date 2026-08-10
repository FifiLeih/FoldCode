# Native source layout

- `bridge/` contains JNI and stable base-app runtime bridges.
- `launchers/` contains small Android-executable hosts for extension-delivered tools.
- `pico/` contains FoldCode-native Pico build helpers.
- `pioasm/` is the vendored official Pico SDK assembler and generated parser sources.

Large compilers, SDKs, and language runtimes are extension payloads; this directory
contains only Android-native code that must ship with the base application.
