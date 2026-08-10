# Android source architecture

All Kotlin sources currently keep the `dev.foldcode.ide` package so JNI entry points,
persisted class names, and the native Git launcher remain stable. Directories express
ownership and should be used for new code:

- `app/` — Android application and activity entry points only.
- `build/` — host compiler orchestration, capability checks, cancellation, and CMake dependencies.
- `debug/` — LLDB, JavaScript, and WebView debug-session orchestration and protocol bridges.
- `editor/intelligence/` — diagnostics, completion, clang, and clangd integration.
- `editor/ui/` — code editor, WebView preview, pointer routing, and editor presentation.
- `extensions/` — package installation, catalog access, and extension state.
- `extensions/ui/` — extension marketplace and details UI.
- `git/` — project persistence, repository operations, credentials, and the native Git CLI entry point.
- `model/` — workspace models, validation, file classification, and shared IDE colors.
- `pico/build/` — Pico SDK/toolchain selection and full CMake build runtime.
- `pico/project/` — Pico board, target, CMake, and example project models.
- `pico/usb/` — BOOTSEL detection and UF2 flashing.
- `pico/ui/` — Pico quick-access presentation.
- `platform/` — stable JNI-facing platform bridges.
- `terminal/` — terminal sessions, shell environments, PTY/process coordination, and input/output state.
- `ui/workspace/` — top-level workspace composition and interaction wiring.
- `web/` — web-project mirroring, package installation, development-server lifecycle, and preview state.

Dependency direction should remain `UI -> feature/core -> platform`. Core build,
Git, extension, and Pico code must not depend on Compose UI types.
