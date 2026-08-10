# Native runtime inputs

This directory is intentionally source-only in Git. Android ARM64 `.so` files placed
here are build inputs produced by the scripts under `tools/` or extracted from verified
toolchain builds. They are not committed because several are generated, platform-specific,
and one exceeds GitHub's normal 100 MB per-file limit.

The base Android build creates its small native launchers from
`app/src/main/cpp/CMakeLists.txt`. Large compiler, debugger, SDK, and language-runtime
libraries belong in FoldCode extension packages and release assets.

Before a reproducible public release, the release pipeline must restore every required
native input from a checksum-pinned build or release bundle. A developer checkout must
not silently depend on untracked binaries from a previous local build.
