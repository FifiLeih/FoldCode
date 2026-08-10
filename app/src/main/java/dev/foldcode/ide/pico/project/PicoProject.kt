package dev.foldcode.ide

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal const val PICO_PROJECT_MARKER = "foldcode-pico.json"

internal data class PicoProjectConfiguration(
    val board: PicoBoard,
    val target: String? = null,
    val networkPolicy: PicoDependencyNetworkPolicy = PicoDependencyNetworkPolicy.Ask,
    val buildType: PicoBuildType = PicoBuildType.Release,
)

internal enum class PicoBuildType(val displayName: String, val cmakeValue: String) {
    Debug("Debug", "Debug"),
    Release("Release", "Release"),
    RelWithDebInfo("Release with debug info", "RelWithDebInfo"),
    MinSizeRel("Minimum size release", "MinSizeRel"),
    ;

    companion object {
        fun fromMarker(value: String?): PicoBuildType = entries.firstOrNull {
            it.cmakeValue.equals(value, ignoreCase = true)
        } ?: Release
    }
}

/** App-private board/target overrides for imported projects; never dirties their repository. */
internal class PicoProjectConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("foldcode_pico_projects", Context.MODE_PRIVATE)

    fun load(projectKey: String): PicoProjectConfiguration? {
        val value = preferences.getString(key(projectKey), null) ?: return null
        val boardId = value.jsonString("board") ?: return null
        val platform = value.jsonString("platform")
        val board = PicoBoard.entries.firstOrNull { it.boardId == boardId && it.platform == platform }
            ?: PicoBoard.entries.firstOrNull { it.boardId == boardId }
            ?: return null
        return PicoProjectConfiguration(
            board = board,
            target = value.jsonString("target"),
            networkPolicy = PicoDependencyNetworkPolicy.fromMarker(value.jsonString("networkPolicy")),
            buildType = PicoBuildType.fromMarker(value.jsonString("buildType")),
        )
    }

    fun save(projectKey: String, configuration: PicoProjectConfiguration) {
        preferences.edit { putString(key(projectKey), picoConfigurationMarker(configuration)) }
    }

    fun remove(projectKey: String) {
        preferences.edit { remove(key(projectKey)) }
    }

    private fun key(projectKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(projectKey.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal data class PicoBuildResult(
    val succeeded: Boolean,
    val output: String,
    val uf2File: File? = null,
)

internal fun findCurrentPicoUf2(projectDirectory: File, target: String?): File? {
    val buildDirectory = File(projectDirectory, "build")
    if (!buildDirectory.isDirectory) return null
    val candidates = buildDirectory.walkTopDown()
        .filter { it.isFile && it.extension.equals("uf2", ignoreCase = true) && it.isValidUf2() }
        .toList()
    val artifact = target?.takeIf(String::isNotBlank)?.let { configuredTarget ->
        candidates.filter { it.nameWithoutExtension == configuredTarget }.maxByOrNull(File::lastModified)
    } ?: candidates.maxByOrNull(File::lastModified)
    artifact ?: return null

    val newestInput = projectDirectory.walkTopDown()
        .onEnter { directory ->
            directory == projectDirectory || directory.parentFile != projectDirectory ||
                directory.name !in setOf("build", "target", ".git", ".foldcode")
        }
        .filter(File::isFile)
        .maxOfOrNull(File::lastModified)
        ?: 0L
    return artifact.takeIf { it.lastModified() >= newestInput }
}

private fun File.isValidUf2(): Boolean {
    if (length() < 512L || length() % 512L != 0L) return false
    return runCatching {
        FileInputStream(this).use { input ->
            val magic = ByteArray(8)
            input.read(magic) == magic.size && magic.contentEquals(
                byteArrayOf(0x55, 0x46, 0x32, 0x0A, 0x57, 0x51, 0x5D, 0x9E.toByte()),
            )
        }
    }.getOrDefault(false)
}

internal data class PicoCMakeAnalysis(
    val targets: List<String>,
    val requiresFullCMake: Boolean,
    val reasons: List<String>,
)

internal enum class PicoChip(
    val platform: String,
    val sdkSourcePlatform: String,
    val targetTriple: String,
    val cpu: String,
    val uf2Family: Long,
    val flashBytes: Int,
) {
    Rp2040("rp2040", "rp2040", "arm-none-eabi", "cortex-m0plus", 0xE48BFF56L, 2 * 1024 * 1024),
    Rp2350("rp2350", "rp2350", "arm-none-eabi", "cortex-m33", 0xE48BFF59L, 4 * 1024 * 1024),
    Rp2350RiscV("rp2350-riscv", "rp2350", "riscv32-unknown-elf", "hazard3", 0xE48BFF59L, 4 * 1024 * 1024),
    ;

    val riscV get() = this == Rp2350RiscV
}

/** A board selects a chip-specific bundle produced from the shared SDK release. */
internal enum class PicoBoard(
    val boardId: String,
    val displayName: String,
    val chip: PicoChip,
    val bundleId: String,
) {
    Pico("pico", "Raspberry Pi Pico (RP2040)", PicoChip.Rp2040, "rp2040"),
    PicoW("pico_w", "Raspberry Pi Pico W (RP2040 + Wi-Fi)", PicoChip.Rp2040, "pico_w"),
    Pico2("pico2", "Raspberry Pi Pico 2 (RP2350 Arm)", PicoChip.Rp2350, "rp2350"),
    Pico2W("pico2_w", "Raspberry Pi Pico 2 W (RP2350 Arm + Wi-Fi)", PicoChip.Rp2350, "pico2_w"),
    Pico2RiscV("pico2", "Raspberry Pi Pico 2 (RP2350 RISC-V)", PicoChip.Rp2350RiscV, "rp2350-riscv"),
    ;

    val platform get() = chip.platform
    val cpu get() = chip.cpu
    val uf2Family get() = chip.uf2Family
    val flashBytes get() = chip.flashBytes
}

/** Curated, self-contained projects adapted from the official Raspberry Pi pico-examples catalog. */
internal enum class PicoExample(
    val displayName: String,
    val description: String,
    val sdkLibraries: Set<String> = emptySet(),
) {
    Blink("Blink LED", "Drive the board's built-in status LED"),
    UartHello("USB/UART hello", "Stream a board identification message once per second"),
    GpioInterrupt(
        "GPIO interrupt counter",
        "Count falling-edge interrupts on GP15",
        setOf("hardware_gpio"),
    ),
    PwmLedFade(
        "PWM LED fade",
        "Fade an external LED on GP15 with a PWM wrap interrupt",
        setOf("hardware_pwm", "hardware_irq"),
    ),
    OnboardTemperature(
        "On-board temperature",
        "Sample and convert the internal ADC temperature sensor",
        setOf("hardware_adc"),
    ),
    I2cBusScan(
        "I²C bus scanner",
        "Scan the default I²C bus (GP4/GP5 on Pico boards)",
        setOf("hardware_i2c"),
    ),
    MulticoreFifo(
        "Multicore FIFO",
        "Exchange and transform values between both processor cores",
        setOf("pico_multicore"),
    ),
    ;

    fun supports(language: PicoProjectLanguage): Boolean =
        language == PicoProjectLanguage.C || language == PicoProjectLanguage.Cpp
}

internal enum class PicoProjectLanguage(val displayName: String, val sourceFile: String) {
    C("C", "main.c"),
    Cpp("C++", "main.cpp"),
    MicroPython("MicroPython", "main.py"),
    Rust("Rust", "src/main.rs"),
}

internal fun isPicoMicroPythonProject(files: Map<String, String>): Boolean =
    files[PICO_PROJECT_MARKER].orEmpty().jsonString("runtime") == "micropython"

internal fun isPicoRustProject(files: Map<String, String>): Boolean =
    files[PICO_PROJECT_MARKER].orEmpty().jsonString("runtime") == "rust"

/** A regular Cargo workspace, including imported desktop Rust projects. */
internal fun isRustCargoProject(files: Map<String, String>): Boolean =
    files.containsKey("Cargo.toml") && files.keys.any { it.endsWith(".rs", ignoreCase = true) }

internal fun picoBoard(files: Map<String, String>): PicoBoard {
    val marker = files[PICO_PROJECT_MARKER].orEmpty()
    val cmake = files["CMakeLists.txt"].orEmpty()
    val platform = marker.jsonString("platform")
        ?: cmake.cmakeSetValue("PICO_PLATFORM")
    val boardId = marker.jsonString("board")
        ?: cmake.cmakeSetValue("PICO_BOARD")
    return when {
        platform == PicoChip.Rp2350RiscV.platform -> PicoBoard.Pico2RiscV
        boardId == PicoBoard.Pico2W.boardId -> PicoBoard.Pico2W
        boardId == PicoBoard.PicoW.boardId -> PicoBoard.PicoW
        boardId == PicoBoard.Pico2.boardId -> PicoBoard.Pico2
        boardId == PicoBoard.Pico.boardId -> PicoBoard.Pico
        platform == PicoChip.Rp2350.platform -> PicoBoard.Pico2
        else -> PicoBoard.Pico
    }
}

internal fun isPicoProject(files: Map<String, String>): Boolean {
    if (files.containsKey(PICO_PROJECT_MARKER)) return true
    return isPicoSdkProject(files)
}

/** True only for projects compiled through the Pico SDK CMake pipeline. */
internal fun isPicoSdkProject(files: Map<String, String>): Boolean {
    if (files.containsKey(PICO_PROJECT_MARKER) &&
        !isPicoMicroPythonProject(files) && !isPicoRustProject(files)
    ) return true
    val cmake = files["CMakeLists.txt"].orEmpty()
    return Regex("\\bpico_sdk_init\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(cmake) &&
        (Regex("\\binclude\\s*\\(\\s*pico_sdk_import\\.cmake", RegexOption.IGNORE_CASE).containsMatchIn(cmake) ||
            Regex("\\bpico_add_extra_outputs\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(cmake))
}

internal fun picoProjectConfiguration(files: Map<String, String>): PicoProjectConfiguration {
    val marker = files[PICO_PROJECT_MARKER].orEmpty()
    val analysis = analyzePicoCMake(files["CMakeLists.txt"].orEmpty())
    return PicoProjectConfiguration(
        board = picoBoard(files),
        target = marker.jsonString("target") ?: analysis.targets.singleOrNull(),
        networkPolicy = PicoDependencyNetworkPolicy.fromMarker(marker.jsonString("networkPolicy")),
        buildType = PicoBuildType.fromMarker(marker.jsonString("buildType")),
    )
}

/** Extracts CMake targets and advanced project features for configuration hints. */
internal fun analyzePicoCMake(cmake: String): PicoCMakeAnalysis {
    val targets = Regex("\\badd_executable\\s*\\(\\s*([^\\s)]+)", RegexOption.IGNORE_CASE)
        .findAll(cmake)
        .map { it.groupValues[1] }
        .filterNot { it.startsWith("${'$'}") }
        .distinct()
        .toList()
    val reasons = buildList {
        if (targets.size > 1) add("multiple executable targets (${targets.joinToString()})")
        val advancedFunctions = linkedMapOf(
            "pico_set_binary_type" to "non-default binary type",
            "pico_set_linker_script" to "custom linker script",
            "add_linker_script" to "generated linker script",
            "add_custom_command" to "generated build output",
            "pico_sign_binary" to "binary signing",
            "pico_hash_binary" to "binary hashing",
            "pico_encrypt_binary" to "binary encryption",
            "pico_embed_pt_in_binary" to "embedded partition table",
            "pico_set_otp_key_output_file" to "OTP output",
            "pico_package_uf2_output" to "packaged UF2 output",
            "target_compile_definitions" to "target-specific compile definitions",
            "target_compile_options" to "target-specific compile options",
            "target_link_options" to "target-specific linker options",
            "target_include_directories" to "target-specific include directories",
        )
        advancedFunctions.forEach { (function, description) ->
            if (Regex("\\b${Regex.escape(function)}\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(cmake)) {
                add(description)
            }
        }
        if (Regex("\\bfunction\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(cmake)) add("custom CMake functions")
    }.distinct()
    return PicoCMakeAnalysis(targets, reasons.isNotEmpty(), reasons)
}

internal fun picoConfigurationMarker(
    configuration: PicoProjectConfiguration,
    runtime: String? = null,
): String = buildString {
    append("{\"platform\":\"").append(configuration.board.platform)
    append("\",\"board\":\"").append(configuration.board.boardId)
    append("\",\"sdk\":\"pico-sdk-").append(PicoSdkRelease.sdkVersion)
    append("\",\"toolchain\":\"gnu-").append(PicoSdkRelease.gccVersion)
    append("\",\"buildSystem\":\"cmake\"")
    append(",\"networkPolicy\":\"").append(configuration.networkPolicy.markerValue).append('"')
    append(",\"buildType\":\"").append(configuration.buildType.cmakeValue).append('"')
    configuration.target?.takeIf(String::isNotBlank)?.let { target ->
        append(",\"target\":\"").append(target.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
    }
    runtime?.takeIf(String::isNotBlank)?.let { value ->
        append(",\"runtime\":\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
    }
    append(",\"version\":").append(if (runtime == null) 8 else 9).append('}')
}

private fun String.jsonString(name: String): String? =
    Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"([^\"]+)\"")
        .find(this)?.groupValues?.get(1)

private fun String.cmakeSetValue(name: String): String? =
    Regex("\\bset\\s*\\(\\s*${Regex.escape(name)}\\s+([^\\s)]+)", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.get(1)?.trim('"')

private fun cmake(board: PicoBoard, language: PicoProjectLanguage): String {
    val wirelessArchitecture = picoWirelessArchitectureTarget(board)?.let { " $it" }.orEmpty()
    val wirelessIncludes = if (board == PicoBoard.PicoW || board == PicoBoard.Pico2W) {
        "target_include_directories(${'$'}{FOLDCODE_TARGET} PRIVATE ${'$'}{CMAKE_CURRENT_LIST_DIR})\n"
    } else {
        ""
    }
    return """cmake_minimum_required(VERSION 3.13)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(PICO_BOARD ${board.boardId} CACHE STRING "Target board")
set(PICO_PLATFORM ${board.platform} CACHE STRING "Target architecture")
include(pico_sdk_import.cmake)
get_filename_component(FOLDCODE_PROJECT_NAME "${'$'}{CMAKE_CURRENT_SOURCE_DIR}" NAME)
string(MAKE_C_IDENTIFIER "${'$'}{FOLDCODE_PROJECT_NAME}" FOLDCODE_TARGET)
project(${'$'}{FOLDCODE_TARGET} C CXX ASM)
pico_sdk_init()
add_executable(${'$'}{FOLDCODE_TARGET} ${language.sourceFile})
target_link_libraries(${'$'}{FOLDCODE_TARGET} pico_stdlib$wirelessArchitecture)
${wirelessIncludes}pico_enable_stdio_usb(${'$'}{FOLDCODE_TARGET} 1)
pico_enable_stdio_uart(${'$'}{FOLDCODE_TARGET} 1)
pico_add_extra_outputs(${'$'}{FOLDCODE_TARGET})
"""
}

/** The W-board LED needs CYW43 initialization, but it does not need the lwIP network stack. */
internal fun picoWirelessArchitectureTarget(board: PicoBoard): String? =
    if (board == PicoBoard.PicoW || board == PicoBoard.Pico2W) "pico_cyw43_arch_none" else null

internal fun upgradeWirelessBlinkCmake(cmake: String): String {
    val withoutLwip = cmake.replace("pico_cyw43_arch_lwip_poll", "pico_cyw43_arch_none")
    val includeDirective =
        "target_include_directories(${'$'}{FOLDCODE_TARGET} PRIVATE ${'$'}{CMAKE_CURRENT_LIST_DIR})"
    if (includeDirective in withoutLwip) return withoutLwip
    val linkLine = withoutLwip.lineSequence()
        .firstOrNull { it.startsWith("target_link_libraries(${'$'}{FOLDCODE_TARGET} ") }
        ?: return withoutLwip
    return withoutLwip.replace(linkLine, "$linkLine\n$includeDirective")
}

private fun marker(board: PicoBoard, runtime: String = "pico-sdk"): String =
    picoConfigurationMarker(PicoProjectConfiguration(board = board), runtime)

private fun picoSdkImport(context: Context): String =
    context.assets.open("templates/pico_sdk_import.cmake").bufferedReader().use { it.readText() }

internal fun picoEmptyProject(
    context: Context,
    board: PicoBoard,
    language: PicoProjectLanguage = PicoProjectLanguage.Cpp,
): Map<String, String> = when (language) {
    PicoProjectLanguage.MicroPython -> picoMicroPythonProject(board)
    PicoProjectLanguage.Rust -> picoRustProject(board)
    PicoProjectLanguage.C, PicoProjectLanguage.Cpp -> linkedMapOf(
    language.sourceFile to """#include "pico/stdlib.h"

int main() {
    stdio_init_all();

    while (true) {
        tight_loop_contents();
    }
}
""",
    "CMakeLists.txt" to cmake(board, language),
    "pico_sdk_import.cmake" to picoSdkImport(context),
    "README.md" to "# FoldCode ${board.displayName} project\n\nCompile in the Raspberry Pi Pico panel.",
    PICO_PROJECT_MARKER to marker(board),
).let { project ->
    if (board == PicoBoard.PicoW || board == PicoBoard.Pico2W) project + (
        "lwipopts.h" to """#pragma once
#define NO_SYS 1
#define LWIP_DHCP 1
#define LWIP_DNS 1
#define LWIP_RAW 1
#define LWIP_NETIF_HOSTNAME 1
#define LWIP_NETIF_STATUS_CALLBACK 1
#define LWIP_SOCKET 0
#define LWIP_NETCONN 0
"""
    ) else project
    }
}

private fun picoMicroPythonProject(board: PicoBoard): Map<String, String> = linkedMapOf(
    "main.py" to """from machine import Pin
import time

led = Pin("LED", Pin.OUT)

while True:
    led.toggle()
    time.sleep_ms(500)
""",
    "README.md" to """# MicroPython · ${board.displayName}

`main.py` runs on the board. Install the Python extension for editing intelligence and the MicroPython Pico component for firmware and USB deployment.
""",
    PICO_PROJECT_MARKER to marker(board, "micropython"),
)

private fun picoRustProject(board: PicoBoard): Map<String, String> {
    val target = when (board.chip) {
        PicoChip.Rp2040 -> "thumbv6m-none-eabi"
        PicoChip.Rp2350 -> "thumbv8m.main-none-eabihf"
        PicoChip.Rp2350RiscV -> "riscv32imac-unknown-none-elf"
    }
    val rp2040 = board.chip == PicoChip.Rp2040
    val dependencies = if (rp2040) {
        """embedded-hal = "1.0.0"
panic-halt = "0.2.0"
rp2040-boot2 = "0.3.0"
rp2040-hal = { version = "0.12.0", features = ["binary-info", "critical-section-impl", "rt"] }"""
    } else {
        """embedded-hal = "1.0.0"
panic-halt = "0.2.0"
rp235x-hal = { version = "0.4.0", features = ["binary-info", "critical-section-impl", "rt"] }"""
    }
    val rustFlags = when (board.chip) {
        PicoChip.Rp2040 -> """    "-C", "link-arg=--nmagic",
    "-C", "link-arg=-Tlink.x",
    "-C", "no-vectorize-loops","""
        PicoChip.Rp2350 -> """    "-C", "link-arg=--nmagic",
    "-C", "link-arg=-Tlink.x",
    "-C", "target-cpu=cortex-m33","""
        PicoChip.Rp2350RiscV -> """    "-C", "link-arg=--nmagic",
    "-C", "link-arg=-Trp235x_riscv.x","""
    }
    return linkedMapOf(
        "Cargo.toml" to """[package]
name = "foldcode-pico"
version = "0.1.0"
edition = "2021"

[dependencies]
$dependencies

[profile.release]
debug = 2
lto = true
opt-level = "s"
""",
        ".cargo/config.toml" to """[build]
target = "$target"

[target.$target]
rustflags = [
$rustFlags
]
""",
        "memory.x" to if (rp2040) rustRp2040MemoryLayout() else rustRp2350MemoryLayout(),
        "src/main.rs" to if (rp2040) rustRp2040Main() else rustRp2350Main(),
        "README.md" to """# Rust · ${board.displayName}

Target: `$target`. Install the Rust and Raspberry Pi Pico extensions before building.

This starter follows the current `rp-rs/rp-hal` layout. It drives GPIO25, which is the
on-board LED on Pico and Pico 2. Pico W boards use the CYW43 Wi-Fi chip for their LED,
so replace the GPIO section with a CYW43 driver when targeting a W board.
""",
        PICO_PROJECT_MARKER to marker(board, "rust"),
    )
}

private fun rustRp2040Main(): String = """#![no_std]
#![no_main]

use panic_halt as _;
use rp2040_hal as hal;
use embedded_hal::delay::DelayNs;
use embedded_hal::digital::OutputPin;

#[link_section = ".boot2"]
#[used]
pub static BOOT2: [u8; 256] = rp2040_boot2::BOOT_LOADER_GENERIC_03H;

const XTAL_FREQ_HZ: u32 = 12_000_000;

#[hal::entry]
fn main() -> ! {
    let mut pac = hal::pac::Peripherals::take().unwrap();
    let mut watchdog = hal::Watchdog::new(pac.WATCHDOG);
    let clocks = hal::clocks::init_clocks_and_plls(
        XTAL_FREQ_HZ,
        pac.XOSC,
        pac.CLOCKS,
        pac.PLL_SYS,
        pac.PLL_USB,
        &mut pac.RESETS,
        &mut watchdog,
    )
    .unwrap();
    let mut timer = hal::Timer::new(pac.TIMER, &mut pac.RESETS, &clocks);
    let sio = hal::Sio::new(pac.SIO);
    let pins = hal::gpio::Pins::new(
        pac.IO_BANK0,
        pac.PADS_BANK0,
        sio.gpio_bank0,
        &mut pac.RESETS,
    );
    let mut led = pins.gpio25.into_push_pull_output();

    loop {
        led.set_high().unwrap();
        timer.delay_ms(500);
        led.set_low().unwrap();
        timer.delay_ms(500);
    }
}
"""

private fun rustRp2350Main(): String = """#![no_std]
#![no_main]

use panic_halt as _;
use rp235x_hal as hal;
use embedded_hal::delay::DelayNs;
use embedded_hal::digital::OutputPin;

#[link_section = ".start_block"]
#[used]
pub static IMAGE_DEF: hal::block::ImageDef = hal::block::ImageDef::secure_exe();

const XTAL_FREQ_HZ: u32 = 12_000_000;

#[hal::entry]
fn main() -> ! {
    let mut pac = hal::pac::Peripherals::take().unwrap();
    let mut watchdog = hal::Watchdog::new(pac.WATCHDOG);
    let clocks = hal::clocks::init_clocks_and_plls(
        XTAL_FREQ_HZ,
        pac.XOSC,
        pac.CLOCKS,
        pac.PLL_SYS,
        pac.PLL_USB,
        &mut pac.RESETS,
        &mut watchdog,
    )
    .unwrap();
    let mut timer = hal::Timer::new_timer0(pac.TIMER0, &mut pac.RESETS, &clocks);
    let sio = hal::Sio::new(pac.SIO);
    let pins = hal::gpio::Pins::new(
        pac.IO_BANK0,
        pac.PADS_BANK0,
        sio.gpio_bank0,
        &mut pac.RESETS,
    );
    let mut led = pins.gpio25.into_push_pull_output();

    loop {
        led.set_high().unwrap();
        timer.delay_ms(500);
        led.set_low().unwrap();
        timer.delay_ms(500);
    }
}
"""

private fun rustRp2040MemoryLayout(): String = """MEMORY {
    BOOT2 : ORIGIN = 0x10000000, LENGTH = 0x100
    FLASH : ORIGIN = 0x10000100, LENGTH = 2048K - 0x100
    RAM : ORIGIN = 0x20000000, LENGTH = 256K
    SRAM4 : ORIGIN = 0x20040000, LENGTH = 4K
    SRAM5 : ORIGIN = 0x20041000, LENGTH = 4K
}

EXTERN(BOOT2_FIRMWARE)

SECTIONS {
    .boot2 ORIGIN(BOOT2) : { KEEP(*(.boot2)); } > BOOT2
} INSERT BEFORE .text;

SECTIONS {
    .boot_info : ALIGN(4) { KEEP(*(.boot_info)); } > FLASH
} INSERT AFTER .vector_table;

_stext = ADDR(.boot_info) + SIZEOF(.boot_info);

SECTIONS {
    .bi_entries : ALIGN(4) {
        __bi_entries_start = .;
        KEEP(*(.bi_entries));
        . = ALIGN(4);
        __bi_entries_end = .;
    } > FLASH
} INSERT AFTER .text;

SECTIONS {
    .flash_end : { __flash_binary_end = .; } > FLASH
} INSERT AFTER .uninit;
"""

private fun rustRp2350MemoryLayout(): String = """MEMORY {
    FLASH : ORIGIN = 0x10000000, LENGTH = 4096K
    RAM : ORIGIN = 0x20000000, LENGTH = 512K
    SRAM8 : ORIGIN = 0x20080000, LENGTH = 4K
    SRAM9 : ORIGIN = 0x20081000, LENGTH = 4K
}

SECTIONS {
    .start_block : ALIGN(4) {
        __start_block_addr = .;
        KEEP(*(.start_block));
        KEEP(*(.boot_info));
    } > FLASH
} INSERT AFTER .vector_table;

_stext = ADDR(.start_block) + SIZEOF(.start_block);

SECTIONS {
    .bi_entries : ALIGN(4) {
        __bi_entries_start = .;
        KEEP(*(.bi_entries));
        . = ALIGN(4);
        __bi_entries_end = .;
    } > FLASH
} INSERT AFTER .text;

SECTIONS {
    .end_block : ALIGN(4) {
        __end_block_addr = .;
        KEEP(*(.end_block));
        __flash_binary_end = .;
    } > FLASH
} INSERT AFTER .uninit;

PROVIDE(start_to_end = __end_block_addr - __start_block_addr);
PROVIDE(end_to_start = __start_block_addr - __end_block_addr);
"""

/** Upgrade only the untouched register-level example generated by FoldCode 0.2. */
internal fun upgradeGeneratedPicoExample(context: Context, files: Map<String, String>): Map<String, String> {
    val main = files["main.cpp"] ?: return files
    val board = picoBoard(files)
    val replacedLegacyExample = isPicoProject(files) &&
        main.startsWith("// FoldCode Raspberry Pi Pico blink example") && "reg(0x400140cc)" in main
    val replacedWirelessGpioExample = isPicoProject(files) &&
        (board == PicoBoard.PicoW || board == PicoBoard.Pico2W) &&
        main == picoBlinkExampleSource(PicoBoard.Pico)
    val upgradedSource = when {
        replacedLegacyExample -> picoExampleProject(context, board, PicoExample.Blink)
        replacedWirelessGpioExample -> files + ("main.cpp" to picoBlinkExampleSource(board))
        else -> files
    }
    val upgraded = if (
        (board == PicoBoard.PicoW || board == PicoBoard.Pico2W) &&
        upgradedSource["main.cpp"] == picoBlinkExampleSource(board)
    ) {
        upgradedSource["CMakeLists.txt"]?.let { cmake ->
            upgradedSource + ("CMakeLists.txt" to upgradeWirelessBlinkCmake(cmake))
        } ?: upgradedSource
    } else {
        upgradedSource
    }
    return when {
        !isPicoProject(upgraded) -> upgraded
        replacedLegacyExample || PICO_PROJECT_MARKER !in upgraded ->
            upgraded + (PICO_PROJECT_MARKER to marker(picoBoard(upgraded)))
        else -> upgraded // Preserve target, board and network policy selected by the user.
    }
}
