# Third-party notices

FoldCode is licensed under the Apache License, Version 2.0. Components listed
below remain subject to their respective licenses.

## Raspberry Pi pioasm

The directory `app/src/main/cpp/pioasm` contains source derived from the
Raspberry Pi Pico SDK `pioasm` tool.

Copyright (c) 2020-2024 Raspberry Pi (Trading) Ltd.

Licensed under the BSD 3-Clause License:

> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice,
>    this list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
>    this list of conditions and the following disclaimer in the documentation
>    and/or other materials provided with the distribution.
> 3. Neither the name of the copyright holder nor the names of its contributors
>    may be used to endorse or promote products derived from this software
>    without specific prior written permission.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
> AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
> IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
> ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
> LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
> CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
> SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
> INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
> CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
> ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
> POSSIBILITY OF SUCH DAMAGE.

Upstream: https://github.com/raspberrypi/pico-sdk

Generated parser files in `app/src/main/cpp/pioasm/gen` include GNU Bison
parser skeleton code, Copyright (C) Free Software Foundation, Inc., distributed
under GNU GPL v3 or later with the Bison parser special exception stated in
those files.

GNU GPL v3: https://www.gnu.org/licenses/gpl-3.0.html

## Mozilla CA certificate bundle

`app/src/main/assets/git/cacert.pem` is generated from Mozilla's root
certificate data using curl's `mk-ca-bundle.pl`. Mozilla certificate data is
made available under the Mozilla Public License 2.0.

Source and update information: https://curl.se/docs/caextract.html

Mozilla Public License 2.0: https://www.mozilla.org/MPL/2.0/

## Arm GNU Toolchain

The separately distributed GNU Arm Embedded FoldCode extension contains the
Arm GNU Toolchain 15.2.Rel1 for an AArch64 Linux host and the
`arm-none-eabi` bare-metal target. The bundle includes GCC, GNU Binutils,
Newlib, runtime libraries, and related components under their respective GNU
and permissive licenses.

The complete consolidated license and copyright notices supplied by Arm are
retained inside the extension runtime as `toolchain/license.txt`. FoldCode's
packaging process treats that file as required and will refuse to build the
extension if it is absent.

Upstream binaries, source snapshot, source manifest, and checksums:
https://developer.arm.com/downloads/-/arm-gnu-toolchain-downloads

GNU GPL v3: https://www.gnu.org/licenses/gpl-3.0.html

When FoldCode publishes the GNU Arm binary extension, the corresponding
15.2.Rel1 source snapshot must be made available alongside it in accordance
with the licenses of the included components.

## Base Android application

The base APK contains the following third-party components. Version numbers
refer to the dependency set used by this source tree.

| Component | Version | License | Upstream |
| --- | --- | --- | --- |
| Kotlin standard library | 2.2.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Kotlin coroutines | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Kotlin serialization core | 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| JetBrains annotations | 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Sora Editor and TextMate integration | 0.24.4 | LGPL-2.1-or-later | https://github.com/Rosemoe/sora-editor |
| JCodings | 1.0.63 | MIT | https://github.com/jruby/jcodings |
| Joni | 2.2.6 | MIT | https://github.com/jruby/joni |
| SnakeYAML Engine | 3.0.1 | Apache-2.0 | https://bitbucket.org/snakeyaml/snakeyaml-engine |
| Gson | 2.13.2 | Apache-2.0 | https://github.com/google/gson |
| Eclipse JDT annotations | 2.4.100 | EPL-2.0 | https://github.com/eclipse-jdt/eclipse.jdt.core |
| Error Prone annotations | 2.41.0 | Apache-2.0 | https://github.com/google/error-prone |
| JSpecify annotations | 1.0.0 | Apache-2.0 | https://github.com/jspecify/jspecify |
| Guava `listenablefuture` compatibility artifact | 1.0 | Apache-2.0 | https://github.com/google/guava |
| AndroidX Activity, Compose UI and Material 3 | Compose BOM 2026.06.01; Activity 1.13.0 | Apache-2.0 | https://github.com/androidx/androidx |
| AndroidX DocumentFile | 1.1.0 | Apache-2.0 | https://github.com/androidx/androidx |
| Android desugar JDK libraries | 2.1.5 | GPL-2.0-with-classpath-exception and other OpenJDK licenses | https://github.com/google/desugar_jdk_libs |
| Android NDK LLVM toolchain resources, libc++, compiler runtime and LLDB server | NDK 27.2.12479018 / LLVM 18 | Apache-2.0 WITH LLVM-exception, Apache-2.0 and bundled NDK component licenses | https://github.com/android/ndk |
| LLVM command-line tools and LLD | 21.1.8 | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |
| CMake | 4.0 runtime | BSD-3-Clause | https://gitlab.kitware.com/cmake/cmake |
| Ninja | packaged Android runtime | Apache-2.0 | https://github.com/ninja-build/ninja |
| Git | 2.55.0 | GPL-2.0-only | https://git-scm.com/ |
| curl | 8.21.0 | curl license | https://curl.se/ |
| OpenSSL | 3.5.7 | Apache-2.0 | https://www.openssl.org/ |
| PRoot | Termux fork revision `6c09638b65797997e33b218a42e5e2c7645cb788` | GPL-2.0-only | https://github.com/termux/proot |
| talloc | 2.4.4 | LGPL-3.0-or-later | https://talloc.samba.org/ |
| GNU C Library loader | Debian 12 Bookworm ARM64 runtime build | LGPL-2.1-or-later | https://www.gnu.org/software/libc/ |

The APK preserves dependency license resources under `META-INF` where supplied
by Android libraries. FoldCode also packages `LICENSE`, `NOTICE`, and this file
under the APK asset directory `legal/`.

Source for the exact FoldCode release is published with its GitHub release.
Corresponding source and license texts for reciprocal-license components are
available from the upstream locations above and must remain available for at
least as long as FoldCode distributes the corresponding binaries.

The TextMate grammar and theme definitions under `app/src/main/editorAssets`
are authored for FoldCode and are not copies of Visual Studio Code grammar
packages, so they are part of FoldCode rather than an additional third-party
distribution.

## Downloadable extensions

Compiler runtimes, SDKs and language servers are delivered separately as
`.fcex` packages. Every current package uses extension schema 3 and must contain
both `licenses/THIRD_PARTY_NOTICES.md` and `licenses/SOURCES.json`. The package
installer rejects schema-3 packages that omit these files. Exact component
versions, licenses and source locations are therefore recorded with the binary
that they describe instead of being inferred from the base APK.

The extension notice sets cover C/C++ (LLVM/Clang), Python, Rust, Web
Development, Raspberry Pi Pico, GNU Arm Embedded, and Fortran & COBOL. They are
stored under the matching directory in `extensions/` and are validated by
`tools/verify-extension-notices.py`.
