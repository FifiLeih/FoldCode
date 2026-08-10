package dev.foldcode.ide

import java.io.File

/** Offline, editor-friendly documentation backed by the installed official SDK. */
internal object PicoDocumentation {
    fun highLevelApis(): String = apiGroup(
        title = "High Level APIs",
        description = "Higher-level SDK functionality built above the hardware interfaces.",
        modules = listOf(
            "pico_aon_timer" to "Always-on timer abstraction for RP2040 and RP2350.",
            "pico_async_context" to "Single-threaded asynchronous work and event contexts.",
            "pico_bootsel_via_double_reset" to "Enter BOOTSEL after a configurable double reset.",
            "pico_flash" to "Higher-level flash access.",
            "pico_i2c_slave" to "Interrupt-driven I²C slave support.",
            "pico_low_power" to "Lower-power state APIs.",
            "pico_multicore" to "Launch and communicate with the second processor core.",
            "pico_rand" to "Random-number generation.",
            "pico_sha256" to "SHA-256 with hardware acceleration where available.",
            "pico_status_led" to "Board-aware access to onboard status LEDs.",
            "pico_stdlib" to "Common SDK library aggregation and utility methods.",
            "pico_sync" to "Mutexes, semaphores, queues and synchronization primitives.",
            "pico_time" to "Timestamps, sleeps, alarms and repeating timers.",
        ),
    )

    fun networkingLibraries(): String = apiGroup(
        title = "Networking Libraries",
        description = "SDK integration for wireless devices, TCP/IP and Bluetooth.",
        modules = listOf(
            "pico_btstack" to "BTstack integration and wrapper libraries.",
            "pico_lwip" to "lwIP TCP/IP integration and configuration targets.",
            "pico_cyw43_driver" to "Low-level CYW43 wireless driver integration.",
            "pico_cyw43_arch" to "Pico W architecture layers combining CYW43 and lwIP.",
        ),
    )

    fun runtimeInfrastructure(): String = apiGroup(
        title = "Runtime Infrastructure",
        description = "Boot, language runtime, C library and optimized arithmetic support.",
        modules = listOf(
            "boot_stage2" to "Second-stage boot loaders for external flash.",
            "pico_atomic" to "C11 atomic helper implementations.",
            "pico_base" to "Core types and macros for the Pico SDK.",
            "pico_binary_info" to "Machine-readable metadata embedded in firmware.",
            "pico_bootrom" to "Access to boot ROM functions and data.",
            "pico_bit_ops" to "Optimized bit-manipulation functions.",
            "pico_clib_interface" to "Glue for the configured C/C++ runtime.",
            "pico_crt0" to "Program entry and exit startup code.",
            "pico_divider" to "Optimized 32-bit and 64-bit division.",
            "pico_double" to "Optimized double-precision floating-point functions.",
            "pico_float" to "Optimized single-precision floating-point functions.",
            "pico_malloc" to "Multicore-safe allocation support.",
        ),
    )

    fun hardwareApis(sdkRoot: File): String = """
# Raspberry Pi Pico hardware APIs

Official Pico SDK ${PicoSdkRelease.sdkVersion} quick reference for RP2040 and RP2350.

The headers referenced below live in:
`${sdkRoot.absolutePath}/src`

## GPIO — `hardware/gpio.h`

Link: `hardware_gpio`

```c
gpio_init(pin);
gpio_set_dir(pin, GPIO_OUT);       // or GPIO_IN
gpio_put(pin, true);
bool level = gpio_get(pin);
gpio_pull_up(pin);
gpio_pull_down(pin);
gpio_set_function(pin, GPIO_FUNC_UART); // SPI, I2C, PWM, PIO0...
```

Interrupts:

```c
gpio_set_irq_enabled_with_callback(pin, GPIO_IRQ_EDGE_RISE | GPIO_IRQ_EDGE_FALL, true, callback);
gpio_acknowledge_irq(pin, events);
```

## UART — `hardware/uart.h`

Link: `hardware_uart`

```c
uart_init(uart0, 115200);
gpio_set_function(0, GPIO_FUNC_UART);
gpio_set_function(1, GPIO_FUNC_UART);
uart_puts(uart0, "Hello\n");
int value = uart_getc(uart0);
uart_set_hw_flow(uart0, false, false);
uart_set_format(uart0, 8, 1, UART_PARITY_NONE);
```

## I²C — `hardware/i2c.h`

Link: `hardware_i2c`

```c
i2c_init(i2c0, 400 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
i2c_write_blocking(i2c0, address, data, length, false);
i2c_read_blocking(i2c0, address, data, length, false);
```

## SPI — `hardware/spi.h`

Link: `hardware_spi`

```c
spi_init(spi0, 1 * 1000 * 1000);
gpio_set_function(16, GPIO_FUNC_SPI); // RX
gpio_set_function(18, GPIO_FUNC_SPI); // SCK
gpio_set_function(19, GPIO_FUNC_SPI); // TX
spi_write_blocking(spi0, tx, length);
spi_read_blocking(spi0, repeated_tx_byte, rx, length);
spi_write_read_blocking(spi0, tx, rx, length);
```

## PWM — `hardware/pwm.h`

Link: `hardware_pwm`

```c
gpio_set_function(pin, GPIO_FUNC_PWM);
uint slice = pwm_gpio_to_slice_num(pin);
pwm_set_wrap(slice, 65535);
pwm_set_gpio_level(pin, level);
pwm_set_enabled(slice, true);
```

Use `pwm_config`, `pwm_get_default_config`, `pwm_config_set_clkdiv` and
`pwm_init` when the frequency and resolution must be configured together.

## ADC and temperature sensor — `hardware/adc.h`

Link: `hardware_adc`

```c
adc_init();
adc_gpio_init(26);                 // ADC0
adc_select_input(0);
uint16_t raw = adc_read();
adc_set_temp_sensor_enabled(true); // internal sensor
adc_select_input(4);
```

## DMA — `hardware/dma.h`

Link: `hardware_dma`

```c
int channel = dma_claim_unused_channel(true);
dma_channel_config config = dma_channel_get_default_config(channel);
channel_config_set_transfer_data_size(&config, DMA_SIZE_32);
channel_config_set_read_increment(&config, true);
channel_config_set_write_increment(&config, true);
dma_channel_configure(channel, &config, destination, source, count, true);
dma_channel_wait_for_finish_blocking(channel);
```

## PIO — `hardware/pio.h`

Link: `hardware_pio`

Generate a header from a `.pio` source in CMake:

```cmake
pico_generate_pio_header(my_target ${'$'}{CMAKE_CURRENT_LIST_DIR}/protocol.pio)
target_link_libraries(my_target hardware_pio)
```

```c
uint offset = pio_add_program(pio0, &protocol_program);
int sm = pio_claim_unused_sm(pio0, true);
protocol_program_init(pio0, sm, offset, pin);
pio_sm_put_blocking(pio0, sm, value);
uint32_t result = pio_sm_get_blocking(pio0, sm);
```

RP2350 also provides PIO2. A program must be assembled for and loaded into the
PIO instance used by its state machine.

## Timers and alarms — `hardware/timer.h`, `pico/time.h`

Links: `hardware_timer`, `pico_time`

```c
sleep_ms(250);
uint64_t now = time_us_64();
absolute_time_t deadline = make_timeout_time_ms(1000);
add_alarm_in_ms(1000, alarm_callback, user_data, false);
bool cancelled = cancel_alarm(alarm_id);
```

## Watchdog — `hardware/watchdog.h`

Link: `hardware_watchdog`

```c
watchdog_enable(2000, true);
watchdog_update();
watchdog_reboot(0, 0, 0);
bool caused = watchdog_caused_reboot();
```

## Clocks — `hardware/clocks.h`

Link: `hardware_clocks`

```c
uint32_t hz = clock_get_hz(clk_sys);
set_sys_clock_khz(125000, true);
clock_gpio_init(gpio, source, divider);
```

Clock limits and defaults differ between RP2040 and RP2350. Prefer the board
configuration and SDK helpers over hard-coded PLL register values.

## Multicore — `pico/multicore.h`

Link: `pico_multicore`

```c
multicore_launch_core1(core1_entry);
multicore_fifo_push_blocking(value);
uint32_t value = multicore_fifo_pop_blocking();
multicore_reset_core1();
```

Protect shared state using `pico/sync.h`, mutexes, semaphores, critical
sections, queues, atomics, or hardware spin locks as appropriate.

## Flash and unique ID

Headers: `hardware/flash.h`, `pico/unique_id.h`

Links: `hardware_flash`, `pico_unique_id`

```c
flash_range_erase(offset, length);
flash_range_program(offset, data, length);
pico_unique_board_id_t id;
pico_get_unique_board_id(&id);
```

Flash erase/program operations stop execute-in-place access. Follow the SDK's
multicore lockout and interrupt-safety requirements before writing flash.

## USB and standard I/O

Links: `pico_stdio_usb`, `tinyusb_device`, `tinyusb_host`

```cmake
pico_enable_stdio_usb(my_target 1)
pico_enable_stdio_uart(my_target 0)
```

`stdio_init_all()` initializes the transports enabled for the target. TinyUSB
applications should include the appropriate TinyUSB headers and link the
device or host library explicitly.

## Pico W wireless APIs

Header: `pico/cyw43_arch.h`

Common links:

- `pico_cyw43_arch_none`
- `pico_cyw43_arch_poll`
- `pico_cyw43_arch_lwip_threadsafe_background`
- `pico_cyw43_arch_lwip_sys_freertos`

```c
cyw43_arch_init();
cyw43_arch_enable_sta_mode();
cyw43_arch_wifi_connect_timeout_ms(ssid, password, CYW43_AUTH_WPA2_AES_PSK, 30000);
cyw43_arch_gpio_put(CYW43_WL_GPIO_LED_PIN, true); // Pico W onboard LED
cyw43_arch_deinit();
```

## Interrupt controller and synchronization

Headers: `hardware/irq.h`, `hardware/sync.h`, `pico/sync.h`

```c
irq_set_exclusive_handler(IRQ_NUMBER, handler);
irq_set_enabled(IRQ_NUMBER, true);
uint32_t state = save_and_disable_interrupts();
restore_interrupts(state);
```

Use shared IRQ handlers when multiple SDK components own the same IRQ. Keep
interrupt routines short and move blocking work to the main loop or another core.
""".trimIndent()

    fun sdkReference(sdkRoot: File): String {
        val officialMainPage = readOfficialDoc(File(sdkRoot, "docs/mainpage.md"))
        val officialExamples = readOfficialDoc(File(sdkRoot, "docs/examples.md"))
        return """
# Raspberry Pi Pico SDK reference

Installed SDK: ${PicoSdkRelease.sdkVersion}

SDK root: `${sdkRoot.absolutePath}`

This tab is offline. It combines FoldCode's build reference with documentation
sources shipped by the official Pico SDK extension.

## Minimal CMake project

```cmake
cmake_minimum_required(VERSION 3.13)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(PICO_BOARD pico2 CACHE STRING "Board")

include(pico_sdk_import.cmake)
project(my_firmware C CXX ASM)
pico_sdk_init()

add_executable(my_firmware main.cpp)
target_link_libraries(my_firmware pico_stdlib)
pico_add_extra_outputs(my_firmware)
```

FoldCode supplies `PICO_SDK_PATH`, CMake, Ninja, pioasm, picotool and the
configured cross toolchain. Imported projects may keep their own
`pico_sdk_import.cmake`; FoldCode redirects it to the installed SDK.

## Boards and platforms

- `pico` — RP2040 Arm Cortex-M0+
- `pico_w` — RP2040 with CYW43 Wi-Fi/Bluetooth
- `pico2` — RP2350 Arm Cortex-M33
- `pico2_w` — RP2350 with CYW43 Wi-Fi/Bluetooth
- RP2350 RISC-V uses the `rp2350-riscv` FoldCode build platform

Select the board in **Build Configuration**. FoldCode stores imported-project
overrides outside the repository, so opening a PC project does not rewrite it.

## Essential CMake helpers

- `pico_sdk_init()` — initialize SDK targets and platform configuration.
- `pico_add_extra_outputs(target)` — generate ELF, BIN, HEX, DIS and UF2 outputs.
- `pico_enable_stdio_usb(target 1|0)` — enable/disable USB stdio.
- `pico_enable_stdio_uart(target 1|0)` — enable/disable UART stdio.
- `pico_generate_pio_header(target file.pio)` — run pioasm and add the generated header.
- `pico_set_binary_type(target copy_to_ram|no_flash)` — select execution placement.
- `pico_set_linker_script(target file.ld)` — use a custom linker script.
- `pico_set_program_name/description/version/url(target value)` — attach binary metadata.
- `pico_sign_binary`, `pico_hash_binary`, `pico_encrypt_binary` — RP2350 image security helpers.
- `pico_embed_pt_in_binary`, `pico_package_uf2_output` — RP2350 partition/package helpers.

## Common libraries

Core:

- `pico_stdlib`, `pico_time`, `pico_sync`, `pico_multicore`
- `pico_unique_id`, `pico_util`, `pico_bootrom`, `pico_rand`

Hardware:

- `hardware_gpio`, `hardware_uart`, `hardware_i2c`, `hardware_spi`
- `hardware_pwm`, `hardware_adc`, `hardware_dma`, `hardware_pio`
- `hardware_irq`, `hardware_timer`, `hardware_watchdog`, `hardware_clocks`
- `hardware_flash`, `hardware_sync`, `hardware_vreg`, `hardware_rtc`
- RP2350: `hardware_sha256`, `hardware_powman`, `hardware_dcp`, `hardware_riscv`

Connectivity:

- `pico_stdio_uart`, `pico_stdio_usb`, `tinyusb_device`, `tinyusb_host`
- `pico_cyw43_arch_*`, `pico_lwip_*`, `pico_btstack_*`
- `pico_mbedtls`, `pico_sha256`

Link only the libraries used by the target. The official SDK resolves their
transitive include directories, definitions and dependencies.

## Source layout

- `src/common/` — architecture-independent core libraries.
- `src/rp2_common/` — libraries shared by RP2040 and RP2350.
- `src/rp2040/` — RP2040-specific implementation.
- `src/rp2350/` — RP2350-specific implementation.
- `src/boards/include/boards/` — board definitions and default pins.
- `lib/` — TinyUSB, lwIP, BTstack, CYW43, Mbed TLS and other dependencies.
- `cmake/` — platform and toolchain integration.
- `docs/` — official documentation sources shown below.

## Build workflow in FoldCode

1. **Configure CMake** creates `build/build.ninja` and `compile_commands.json`.
2. **Compile Project** builds the selected target or all default targets.
3. **Run Project (USB)** flashes an existing current UF2, compiling only when necessary.
4. **Clean CMake** removes compiled outputs and reconfigures without compiling.
5. The Problems view and editor diagnostics use the configured compile database.

## Assembly and PIO

- `.S` — assembler with C preprocessor.
- `.s` — raw GNU assembler.
- `.asm` — raw GNU assembler registered automatically by FoldCode.
- `.pio` — assembled by SDK CMake through Android-native pioasm.

## Official SDK main page

${officialMainPage.ifBlank { "Official mainpage source is unavailable. Reinstall the Pico core component." }}

## Official examples index

${officialExamples.ifBlank { "Official examples index is unavailable." }}
""".trimIndent()
    }

    private fun readOfficialDoc(file: File): String = runCatching {
        if (!file.isFile || file.length() > MAX_DOC_BYTES) return@runCatching ""
        file.readText().replace(Regex("(?m)^\\s*\\{#[^}]+}\\s*$"), "")
    }.getOrDefault("")

    private fun apiGroup(title: String, description: String, modules: List<Pair<String, String>>): String = buildString {
        appendLine("# Raspberry Pi Pico $title")
        appendLine()
        appendLine("Official Pico SDK ${PicoSdkRelease.sdkVersion} offline summary.")
        appendLine()
        appendLine(description)
        appendLine()
        appendLine("## Modules")
        appendLine()
        modules.forEach { (module, detail) -> appendLine("- `$module` — $detail") }
        appendLine()
        append("Connect to view the complete generated API, including functions, macros, parameters, return values and detailed descriptions.")
    }

    private const val MAX_DOC_BYTES = 512L * 1024L
}
