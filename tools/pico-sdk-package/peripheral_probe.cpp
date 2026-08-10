#include "pico/stdlib.h"
#include "pico/multicore.h"
#include "hardware/i2c.h"
#include "hardware/irq.h"
#include "hardware/pwm.h"
#include "hardware/spi.h"
#include "hardware/uart.h"

static void core1_entry() {
    multicore_fifo_push_blocking(0xC0DEu);
    while (true) tight_loop_contents();
}

static void test_irq() {}

int main() {
    stdio_init_all();
    uart_init(uart0, 115200);
    i2c_init(i2c0, 100000);
    spi_init(spi0, 1000 * 1000);

    const uint slice = pwm_gpio_to_slice_num(PICO_DEFAULT_LED_PIN);
    pwm_set_wrap(slice, 1000);
    pwm_set_enabled(slice, true);

    irq_set_exclusive_handler(TIMER_IRQ_0, test_irq);
    irq_set_enabled(TIMER_IRQ_0, true);
    multicore_launch_core1(core1_entry);

    while (true) tight_loop_contents();
}
