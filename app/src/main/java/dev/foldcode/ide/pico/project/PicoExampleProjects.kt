package dev.foldcode.ide

import android.content.Context

private const val PICO_EXAMPLES_URL = "https://github.com/raspberrypi/pico-examples"

/** Builds one standalone example without requiring a checkout of pico-examples. */
internal fun picoExampleProject(
    context: Context,
    board: PicoBoard,
    example: PicoExample,
    language: PicoProjectLanguage = PicoProjectLanguage.Cpp,
): Map<String, String> {
    require(example.supports(language)) {
        "${example.displayName} is not available for ${language.displayName}"
    }
    val base = picoEmptyProject(context, board, language)
    val cmake = addExampleSdkLibraries(base.getValue("CMakeLists.txt"), example.sdkLibraries)
    return base + mapOf(
        language.sourceFile to picoExampleSource(board, example),
        "CMakeLists.txt" to cmake,
        "README.md" to picoExampleReadme(board, example, language),
    )
}

internal fun addExampleSdkLibraries(cmake: String, libraries: Set<String>): String {
    if (libraries.isEmpty()) return cmake
    val linkLine = cmake.lineSequence()
        .firstOrNull { it.startsWith("target_link_libraries(${'$'}{FOLDCODE_TARGET} ") }
        ?: return cmake
    val missing = libraries.filterNot { library ->
        Regex("(?:^|\\s)${Regex.escape(library)}(?:\\s|\\))").containsMatchIn(linkLine)
    }
    if (missing.isEmpty()) return cmake
    return cmake.replace(linkLine, linkLine.removeSuffix(")") + " ${missing.joinToString(" ")})")
}

internal fun picoExampleSource(board: PicoBoard, example: PicoExample): String = when (example) {
    PicoExample.Blink -> picoBlinkExampleSource(board)
    PicoExample.UartHello -> picoUartHelloSource(board)
    PicoExample.GpioInterrupt -> picoGpioInterruptSource()
    PicoExample.PwmLedFade -> picoPwmLedFadeSource()
    PicoExample.OnboardTemperature -> picoOnboardTemperatureSource()
    PicoExample.I2cBusScan -> picoI2cBusScanSource()
    PicoExample.MulticoreFifo -> picoMulticoreFifoSource()
}

private fun picoExampleReadme(
    board: PicoBoard,
    example: PicoExample,
    language: PicoProjectLanguage,
): String = """# ${example.displayName} · ${board.displayName}

${example.description}.

Language: ${language.displayName}. Compile and flash from the Raspberry Pi Pico panel.

This standalone project is adapted from the official Raspberry Pi
[pico-examples]($PICO_EXAMPLES_URL) patterns and uses its BSD-3-Clause licensing convention.
"""

private const val EXAMPLE_LICENSE_HEADER = """/*
 * Adapted from Raspberry Pi pico-examples.
 * Copyright (c) 2020 Raspberry Pi (Trading) Ltd.
 * SPDX-License-Identifier: BSD-3-Clause
 */
"""

/** Pico W boards route their on-board LED through the CYW43 rather than a GPIO pin. */
internal fun picoBlinkExampleSource(board: PicoBoard): String = EXAMPLE_LICENSE_HEADER +
    if (board == PicoBoard.PicoW || board == PicoBoard.Pico2W) {
        """
#include "pico/stdlib.h"
#include "pico/cyw43_arch.h"

int main() {
    if (cyw43_arch_init()) {
        return 1;
    }
    while (true) {
        cyw43_arch_gpio_put(CYW43_WL_GPIO_LED_PIN, true);
        sleep_ms(500);
        cyw43_arch_gpio_put(CYW43_WL_GPIO_LED_PIN, false);
        sleep_ms(500);
    }
}
"""
    } else {
        """
#include "pico/stdlib.h"

int main() {
    const uint LED_PIN = PICO_DEFAULT_LED_PIN;
    gpio_init(LED_PIN);
    gpio_set_dir(LED_PIN, GPIO_OUT);
    while (true) {
        gpio_put(LED_PIN, true);
        sleep_ms(500);
        gpio_put(LED_PIN, false);
        sleep_ms(500);
    }
}
"""
    }

private fun picoUartHelloSource(board: PicoBoard): String = EXAMPLE_LICENSE_HEADER + """
#include <stdio.h>
#include "pico/stdlib.h"

int main() {
    stdio_init_all();
    while (true) {
        printf("Hello from ${board.displayName}!\\n");
        sleep_ms(1000);
    }
}
"""

private fun picoGpioInterruptSource(): String = EXAMPLE_LICENSE_HEADER + """
#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"

static const uint INPUT_PIN = 15;
static volatile uint32_t edge_count = 0;

static void on_gpio_edge(uint gpio, uint32_t events) {
    if (gpio == INPUT_PIN && (events & GPIO_IRQ_EDGE_FALL)) {
        edge_count++;
    }
}

int main() {
    stdio_init_all();
    gpio_init(INPUT_PIN);
    gpio_set_dir(INPUT_PIN, GPIO_IN);
    gpio_pull_up(INPUT_PIN);
    gpio_set_irq_enabled_with_callback(INPUT_PIN, GPIO_IRQ_EDGE_FALL, true, &on_gpio_edge);

    uint32_t last_reported = UINT32_MAX;
    while (true) {
        uint32_t current = edge_count;
        if (current != last_reported) {
            printf("GP15 falling edges: %lu\\n", (unsigned long) current);
            last_reported = current;
        }
        sleep_ms(20);
    }
}
"""

private fun picoPwmLedFadeSource(): String = EXAMPLE_LICENSE_HEADER + """
#include "pico/stdlib.h"
#include "hardware/irq.h"
#include "hardware/pwm.h"

static const uint LED_PIN = 15;
static uint pwm_slice;
static uint16_t level = 0;
static bool rising = true;

static void on_pwm_wrap() {
    pwm_clear_irq(pwm_slice);
    if (rising) {
        level++;
        if (level >= 255) rising = false;
    } else {
        level--;
        if (level == 0) rising = true;
    }
    pwm_set_gpio_level(LED_PIN, level * level);
}

int main() {
    gpio_set_function(LED_PIN, GPIO_FUNC_PWM);
    pwm_slice = pwm_gpio_to_slice_num(LED_PIN);
    pwm_clear_irq(pwm_slice);
    pwm_set_irq_enabled(pwm_slice, true);
    irq_set_exclusive_handler(PWM_IRQ_WRAP, on_pwm_wrap);
    irq_set_enabled(PWM_IRQ_WRAP, true);

    pwm_config config = pwm_get_default_config();
    pwm_config_set_clkdiv(&config, 4.0f);
    pwm_init(pwm_slice, &config, true);

    while (true) tight_loop_contents();
}
"""

private fun picoOnboardTemperatureSource(): String = EXAMPLE_LICENSE_HEADER + """
#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/adc.h"

int main() {
    stdio_init_all();
    adc_init();
    adc_set_temp_sensor_enabled(true);
    adc_select_input(4);

    const float conversion = 3.3f / (1 << 12);
    while (true) {
        float voltage = (float) adc_read() * conversion;
        float temperature_c = 27.0f - (voltage - 0.706f) / 0.001721f;
        printf("Internal temperature: %.2f C\\n", temperature_c);
        sleep_ms(1000);
    }
}
"""

private fun picoI2cBusScanSource(): String = EXAMPLE_LICENSE_HEADER + """
#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"

static bool reserved_address(uint8_t address) {
    return (address & 0x78) == 0 || (address & 0x78) == 0x78;
}

int main() {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    printf("\\nI2C scan on SDA=GP4, SCL=GP5\\n   0 1 2 3 4 5 6 7 8 9 A B C D E F\\n");
    for (int address = 0; address < 128; address++) {
        if (address % 16 == 0) printf("%02x ", address);
        uint8_t value;
        int result = reserved_address((uint8_t) address)
            ? PICO_ERROR_GENERIC
            : i2c_read_blocking(i2c0, (uint8_t) address, &value, 1, false);
        printf(result < 0 ? "." : "@");
        printf(address % 16 == 15 ? "\\n" : " ");
    }
    printf("Scan complete.\\n");
    return 0;
}
"""

private fun picoMulticoreFifoSource(): String = EXAMPLE_LICENSE_HEADER + """
#include <stdio.h>
#include "pico/stdlib.h"
#include "pico/multicore.h"

static void core1_main() {
    while (true) {
        uint32_t value = multicore_fifo_pop_blocking();
        multicore_fifo_push_blocking(value * value);
    }
}

int main() {
    stdio_init_all();
    multicore_launch_core1(core1_main);

    for (uint32_t value = 1; ; value++) {
        multicore_fifo_push_blocking(value);
        uint32_t result = multicore_fifo_pop_blocking();
        printf("core 1: %lu squared = %lu\\n", (unsigned long) value, (unsigned long) result);
        sleep_ms(750);
    }
}
"""
