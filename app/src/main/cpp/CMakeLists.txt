cmake_minimum_required(VERSION 3.22.1)
project(foldcode_native LANGUAGES CXX)

# Small full-build host kept in the base app. Large SDK/toolchain data is supplied
# by signed/hash-verified FoldCode extension packages, never as downloaded app code.
add_library(foldpico_runtime SHARED bridge/pico_runtime_bridge.cpp)
target_compile_features(foldpico_runtime PRIVATE cxx_std_20)
target_compile_options(foldpico_runtime PRIVATE -Wall -Wextra -Werror)
target_link_libraries(foldpico_runtime PRIVATE log)
target_link_options(foldpico_runtime PRIVATE "-Wl,-z,max-page-size=16384")

# CMake, Ninja and the Android-native Pico Clang tools inherit this tiny
# preload. Each process reports its own PID to the app-private build session,
# avoiding vendor-specific restrictions on /proc/<pid>/children.
add_library(foldbuildpid SHARED bridge/build_pid_tracker.cpp)
target_compile_features(foldbuildpid PRIVATE cxx_std_17)
target_compile_options(foldbuildpid PRIVATE -Wall -Wextra -Werror)
target_link_options(foldbuildpid PRIVATE "-Wl,-z,max-page-size=16384")

# Clang selects LLD's driver personality from argv[0]. Android only extracts files
# following its native-library naming convention, so this executable wrapper gives
# the packaged core the expected "ld.lld" argv[0].
add_executable(foldlld launchers/lld_wrapper.cpp)
set_target_properties(foldlld PROPERTIES OUTPUT_NAME "foldlld" SUFFIX ".so")
target_link_libraries(foldlld PRIVATE dl)
target_link_options(foldlld PRIVATE "-Wl,-z,max-page-size=16384")

# The server-delivered C/C++ extension may provide libfoldclangdcore.so. This
# small executable remains in the APK so Android's executable-code policy is
# respected while the large, independently updateable clangd core stays in the
# extension payload.
add_executable(foldclangd launchers/clangd_wrapper.cpp)
set_target_properties(foldclangd PROPERTIES OUTPUT_NAME "foldclangd" SUFFIX ".so")
target_link_libraries(foldclangd PRIVATE dl)
target_compile_features(foldclangd PRIVATE cxx_std_17)
target_compile_options(foldclangd PRIVATE -Wall -Wextra -Werror)
target_link_options(foldclangd PRIVATE "-Wl,-z,max-page-size=16384")

# LLDB's large DAP implementation is delivered by the C/C++ extension as a
# shared core. This stable APK-owned entry point satisfies Android's executable
# code policy while allowing the debugger to be updated independently.
add_executable(foldlldbdap launchers/lldb_dap_wrapper.cpp)
set_target_properties(foldlldbdap PROPERTIES OUTPUT_NAME "foldlldbdap" SUFFIX ".so")
target_link_libraries(foldlldbdap PRIVATE dl)
target_compile_features(foldlldbdap PRIVATE cxx_std_17)
target_compile_options(foldlldbdap PRIVATE -Wall -Wextra -Werror)
target_link_options(foldlldbdap PRIVATE "-Wl,-z,max-page-size=16384")

add_executable(folddebuggee launchers/debuggee_launcher.cpp)
set_target_properties(folddebuggee PROPERTIES OUTPUT_NAME "folddebuggee" SUFFIX ".so")
target_compile_features(folddebuggee PRIVATE cxx_std_17)
target_compile_options(folddebuggee PRIVATE -Wall -Wextra -Werror)
target_link_libraries(folddebuggee PRIVATE dl)
target_link_options(folddebuggee PRIVATE "-Wl,-z,max-page-size=16384")

# Official pico-sdk 2.3.0 pioasm, built as an Android ARM64 command-line
# executable. The .so suffix makes PackageManager extract it beside the other
# native tools while it remains directly executable through ProcessBuilder.
add_executable(foldpioasm
        pioasm/main.cpp
        pioasm/pio_assembler.cpp
        pioasm/pio_disassembler.cpp
        pioasm/gen/lexer.cpp
        pioasm/gen/parser.cpp
        pioasm/c_sdk_output.cpp
        pioasm/python_output.cpp
        pioasm/hex_output.cpp
        pioasm/json_output.cpp
        pioasm/ada_output.cpp
        pioasm/go_output.cpp)
set_target_properties(foldpioasm PROPERTIES OUTPUT_NAME "foldpioasm" SUFFIX ".so")
target_include_directories(foldpioasm PRIVATE pioasm pioasm/gen)
target_compile_features(foldpioasm PRIVATE cxx_std_17)
target_link_options(foldpioasm PRIVATE "-Wl,-z,max-page-size=16384")

# Native replacement for the SDK's deterministic RP2040/RP2350 boot padding
# Python scripts. This keeps ordinary official SDK builds Python-free on Android.
add_executable(foldpicopad pico/pico_boot_pad.cpp)
set_target_properties(foldpicopad PROPERTIES OUTPUT_NAME "foldpicopad" SUFFIX ".so")
target_compile_features(foldpicopad PRIVATE cxx_std_17)
target_compile_options(foldpicopad PRIVATE -Wall -Wextra -Werror)
target_link_options(foldpicopad PRIVATE "-Wl,-z,max-page-size=16384")

# Minimal Android-native nm compatible with the ELF objects and archives emitted
# by the Pico cross compilers. The SDK uses it to identify the compiler's actual
# thread-local-storage ABI during configure; no host-side Python or downloaded
# executable code is required.
add_executable(foldnm pico/pico_nm.cpp)
set_target_properties(foldnm PROPERTIES OUTPUT_NAME "foldnm" SUFFIX ".so")
target_compile_features(foldnm PRIVATE cxx_std_17)
target_compile_options(foldnm PRIVATE -Wall -Wextra -Werror)
target_link_options(foldnm PRIVATE "-Wl,-z,max-page-size=16384")

# Python itself is downloaded as an extension. This tiny APK-labelled host loads
# the official python.org Android libpython and exposes normal CLI semantics to CMake.
add_executable(foldpython launchers/python_launcher.cpp)
set_target_properties(foldpython PROPERTIES OUTPUT_NAME "foldpython" SUFFIX ".so")
target_compile_features(foldpython PRIVATE cxx_std_17)
target_compile_options(foldpython PRIVATE -Wall -Wextra -Werror)
target_link_libraries(foldpython PRIVATE dl)
target_link_options(foldpython PRIVATE "-Wl,-z,max-page-size=16384")

add_executable(foldnode launchers/node_launcher.cpp)
set_target_properties(foldnode PROPERTIES OUTPUT_NAME "foldnode" SUFFIX ".so")
target_compile_features(foldnode PRIVATE cxx_std_17)
target_compile_options(foldnode PRIVATE -Wall -Wextra -Werror)
target_link_libraries(foldnode PRIVATE dl)
target_link_options(foldnode PRIVATE "-Wl,-z,max-page-size=16384")

# Upstream Git is packaged as libfoldgitcore.so. This stable APK-labelled entry
# point gives it the conventional `git` argv[0].
add_executable(foldgit launchers/git_launcher.cpp)
set_target_properties(foldgit PROPERTIES OUTPUT_NAME "foldgit" SUFFIX ".so")
target_compile_features(foldgit PRIVATE cxx_std_17)
target_compile_options(foldgit PRIVATE -Wall -Wextra -Werror)
target_link_options(foldgit PRIVATE "-Wl,-z,max-page-size=16384")

# Official Arm GNU executables are Linux/glibc programs. This APK-labelled host
# enters the extension's minimal glibc root through the bundled PRoot engine.
add_executable(foldgnuarm launchers/gnu_arm_launcher.cpp)
set_target_properties(foldgnuarm PROPERTIES OUTPUT_NAME "foldgnuarm" SUFFIX ".so")
target_compile_features(foldgnuarm PRIVATE cxx_std_17)
target_compile_options(foldgnuarm PRIVATE -Wall -Wextra -Werror)
target_link_options(foldgnuarm PRIVATE "-Wl,-z,max-page-size=16384")

# GNU Fortran and GnuCOBOL are delivered together because both use the same
# AArch64 glibc/GCC runtime. This APK-labelled entry point keeps that large
# compiler payload in the independently updateable language extension.
add_executable(foldgnulang launchers/gnu_languages_launcher.cpp)
set_target_properties(foldgnulang PROPERTIES OUTPUT_NAME "foldgnulang" SUFFIX ".so")
target_compile_features(foldgnulang PRIVATE cxx_std_17)
target_compile_options(foldgnulang PRIVATE -Wall -Wextra -Werror)
target_link_options(foldgnulang PRIVATE "-Wl,-z,max-page-size=16384")

# Picotool and libusb are delivered by the Pico extension. Android does not
# permit an ordinary app process to execute downloaded native code directly,
# so this tiny APK-labelled host runs it through the existing PRoot bridge.
add_executable(foldpicotool launchers/picotool_launcher.cpp)
set_target_properties(foldpicotool PROPERTIES OUTPUT_NAME "foldpicotool" SUFFIX ".so")
target_compile_features(foldpicotool PRIVATE cxx_std_17)
target_compile_options(foldpicotool PRIVATE -Wall -Wextra -Werror)
target_link_options(foldpicotool PRIVATE "-Wl,-z,max-page-size=16384")

# OpenOCD is optional Pico-extension content. It runs inside the same translated
# runtime boundary as picotool and GNU Arm; Android USB descriptors are supplied
# by the Kotlin transport when an SWD probe is selected.
add_executable(foldopenocd launchers/openocd_launcher.cpp)
set_target_properties(foldopenocd PROPERTIES OUTPUT_NAME "foldopenocd" SUFFIX ".so")
target_compile_features(foldopenocd PRIVATE cxx_std_17)
target_compile_options(foldopenocd PRIVATE -Wall -Wextra -Werror)
target_link_options(foldopenocd PRIVATE "-Wl,-z,max-page-size=16384")

# Rust, Cargo and rust-analyzer are delivered as an AArch64 Linux toolchain.
# This executable host keeps downloaded code outside the base APK while running
# it through the same Android-compatible PRoot boundary used by GNU Arm.
add_executable(foldrust launchers/rust_launcher.cpp)
set_target_properties(foldrust PROPERTIES OUTPUT_NAME "foldrust" SUFFIX ".so")
target_compile_features(foldrust PRIVATE cxx_std_17)
target_compile_options(foldrust PRIVATE -Wall -Wextra -Werror)
target_link_options(foldrust PRIVATE "-Wl,-z,max-page-size=16384")

# Cargo's glibc posix_spawn cannot resolve programs inside PRoot's virtual
# filesystem. APK-owned child bridges let it start each Rust tool through the
# same translated runtime without placing downloaded executables in the APK.
foreach(rust_child IN ITEMS rustc rustdoc rustfmt)
    add_executable(fold${rust_child} launchers/rust_launcher.cpp)
    set_target_properties(fold${rust_child} PROPERTIES OUTPUT_NAME "fold${rust_child}" SUFFIX ".so")
    target_compile_features(fold${rust_child} PRIVATE cxx_std_17)
    target_compile_options(fold${rust_child} PRIVATE -Wall -Wextra -Werror)
    target_link_options(fold${rust_child} PRIVATE "-Wl,-z,max-page-size=16384")
endforeach()
