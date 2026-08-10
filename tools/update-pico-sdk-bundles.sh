#!/bin/sh
set -eu

# Rebuild every board-family runtime from one Pico SDK/GNU Arm release.
# Required overrides make SDK updates explicit and reproducible.
SDK_VERSION=${SDK_VERSION:-2.3.0}
GCC_VERSION=${GCC_VERSION:-15.2.1}
PICO_SDK_ROOT=${PICO_SDK_ROOT:-"$HOME/.pico-sdk/sdk/$SDK_VERSION"}
PICO_TOOLCHAIN_ROOT=${PICO_TOOLCHAIN_ROOT:-"$HOME/.pico-sdk/toolchain/15_2_Rel1"}
PICOTOOL_ROOT=${PICOTOOL_ROOT:-"$HOME/.pico-sdk/picotool/$SDK_VERSION"}
CMAKE=${CMAKE:-"$HOME/Library/Android/sdk/cmake/3.22.1/bin/cmake"}
NINJA=${NINJA:-"$HOME/Library/Android/sdk/cmake/3.22.1/bin/ninja"}
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WORK_ROOT=${WORK_ROOT:-"${TMPDIR:-/tmp}/foldcode-pico-sdk-$SDK_VERSION"}

test -f "$PICO_SDK_ROOT/pico_sdk_init.cmake"
test -x "$PICO_TOOLCHAIN_ROOT/bin/arm-none-eabi-gcc"
test -x "$PICOTOOL_ROOT/picotool/picotool"
test -x "$CMAKE"
test -x "$NINJA"

package_target() {
    board=$1
    platform=$2
    cpu_flags=$3
    asset_id=$4
    toolchain_root=${5:-$PICO_TOOLCHAIN_ROOT}
    triple=${6:-arm-none-eabi}
    bundle_gcc_version=${7:-$GCC_VERSION}
    build="$WORK_ROOT/build-$asset_id"
    package="$WORK_ROOT/package-$asset_id"
    archive="$WORK_ROOT/pico-sdk-$asset_id.zip"

    rm -rf "$build" "$package" "$archive"
    "$CMAKE" -S "$ROOT/tools/pico-sdk-package" -B "$build" -G Ninja \
        -DPICO_SDK_PATH="$PICO_SDK_ROOT" \
        -DPICO_TOOLCHAIN_PATH="$toolchain_root" \
        -DPICOTOOL_FETCH_FROM_GIT_PATH="$PICOTOOL_ROOT" \
        -DPICO_BOARD="$board" -DPICO_PLATFORM="$platform" -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM="$NINJA"
    "$NINJA" -C "$build" foldcode_sdk_probe

    mkdir -p "$package/generated" "$package/ld" "$package/raw-objects" "$package/components" \
        "$package/runtime" "$package/sdk" "$package/toolchain/include"
    printf 'sdk.version=%s\ngcc.version=%s\nboard=%s\nplatform=%s\n' \
        "$SDK_VERSION" "$bundle_gcc_version" "$board" "$platform" > "$package/bundle.properties"
    rsync -a "$build/generated/pico_base" "$package/generated/"
    # CMake writes host-absolute board/CMSIS includes into config_autogen.h.
    # Convert them to portable includes resolved by PicoSdkBundle.
    perl -pi -e 's{#include ".*/src/boards/include/(boards/[^\"]+)"}{#include "$1"}' \
        "$package/generated/pico_base/pico/config_autogen.h"
    perl -pi -e 's{#include ".*/src/rp2_common/cmsis/include/(cmsis/[^\"]+)"}{#include "$1"}' \
        "$package/generated/pico_base/pico/config_autogen.h"

    rsync -a "$PICO_SDK_ROOT/src" "$package/sdk/"
    mkdir -p "$package/sdk/lib"
    for dependency in tinyusb cyw43-driver lwip; do
        rsync -a --exclude='.git' "$PICO_SDK_ROOT/lib/$dependency" "$package/sdk/lib/"
    done
    rsync -a --prune-empty-dirs --include='*/' --include='*.o' --exclude='*' \
        "$build/CMakeFiles/" "$package/raw-objects/CMakeFiles/"
    rsync -a --prune-empty-dirs --include='*/' --include='*.o' --exclude='*' \
        "$build/pico-sdk/" "$package/raw-objects/pico-sdk/"

    # Preserve SDK component boundaries as archives. GNU ld extracts only the
    # members required by the project's target_link_libraries graph.
    AR="$toolchain_root/bin/$triple-ar"
    RANLIB="$toolchain_root/bin/$triple-ranlib"
    find "$package/raw-objects" -type f -name '*.o' | while IFS= read -r object; do
        case "$object" in
            *probe.c.o|*CompilerId*|*pico_standard_binary_info*) continue ;;
        esac
        component=$(printf '%s\n' "$object" | sed -E -n 's#^.*/src/[^/]+/([^/]+)/.*#\1#p')
        case "$object" in
            *boot_stage2*) component=pico_boot_stage2 ;;
            */lib/tinyusb/*) component=tinyusb_device ;;
            */lib/cyw43-driver/*) component=cyw43_driver ;;
            */lib/lwip/*) component=lwip ;;
        esac
        component=${component:-pico_core}
        "$AR" q "$package/components/lib$component.a" "$object"
    done
    for library in "$package"/components/*.a; do "$RANLIB" "$library"; done
    find "$package/components" -type f -name '*.a' -exec basename {} \; | sort > "$package/components.list"
    rm -rf "$package/raw-objects"
    cp "$build/pico-sdk/src/rp2_common/pico_standard_link/pico_flash_region.ld" "$package/ld/"
    cp "$build/pico-sdk/src/rp2_common/pico_standard_link/pico_psram_region.ld" "$package/ld/"

    rsync -a "$toolchain_root/$triple/include/" "$package/toolchain/include/"
    rsync -a "$toolchain_root/lib/gcc/$triple/$bundle_gcc_version/include/" "$package/toolchain/include/"
    mkdir -p "$package/toolchain/include/c++"
    rsync -a "$toolchain_root/$triple/include/c++/$bundle_gcc_version" "$package/toolchain/include/c++/"

    compiler="$toolchain_root/bin/$triple-g++"
    for library in libstdc++.a libc.a libm.a libgcc.a libnosys.a; do
        # Intentionally split the fixed flag string into compiler arguments.
        library_path=$($compiler $cpu_flags -print-file-name="$library")
        test -f "$library_path"
        cp "$library_path" "$package/runtime/$library"
    done

    (cd "$package" && zip -qr "$archive" .)
    mkdir -p "$ROOT/artifacts/pico-full-bundles"
    cp "$archive" "$ROOT/artifacts/pico-full-bundles/pico-sdk-$asset_id.zip"
    echo "Updated full Pico bundle pico-sdk-$asset_id.zip · SDK $SDK_VERSION · GNU $bundle_gcc_version"
}

BUNDLES=${BUNDLES:-rp2040,rp2350,pico_w,pico2_w,rp2350-riscv}
case ",$BUNDLES," in *,rp2040,*) package_target pico rp2040 "-mcpu=cortex-m0plus -mthumb" rp2040 ;; esac
case ",$BUNDLES," in *,rp2350,*) package_target pico2 rp2350 "-mcpu=cortex-m33 -mthumb -march=armv8-m.main+fp+dsp -mfloat-abi=softfp -mcmse" rp2350 ;; esac
case ",$BUNDLES," in *,pico_w,*) package_target pico_w rp2040 "-mcpu=cortex-m0plus -mthumb" pico_w ;; esac
case ",$BUNDLES," in *,pico2_w,*) package_target pico2_w rp2350 "-mcpu=cortex-m33 -mthumb -march=armv8-m.main+fp+dsp -mfloat-abi=softfp -mcmse" pico2_w ;; esac
case ",$BUNDLES," in *,rp2350-riscv,*)
    RISCV_TOOLCHAIN_ROOT=${PICO_RISCV_TOOLCHAIN_ROOT:-/private/tmp/foldcode-riscv-toolchain}
    package_target pico2 rp2350-riscv "-march=rv32imac_zicsr_zifencei_zaamo_zalrsc_zba_zbb_zbkb_zbs -mabi=ilp32" \
        rp2350-riscv "$RISCV_TOOLCHAIN_ROOT" riscv32-unknown-elf 15.2.0
;; esac
