cmake_minimum_required(VERSION 3.13)

include(${PICO_SDK_PATH}/pico_sdk_init.cmake)
project(foldcode_pico_sdk_bundle C CXX ASM)
pico_sdk_init()

add_executable(foldcode_sdk_probe probe.c)
target_include_directories(foldcode_sdk_probe PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(foldcode_sdk_probe
    pico_stdlib
    pico_multicore
    pico_rand
    pico_unique_id
    hardware_adc
    hardware_clocks
    hardware_dma
    hardware_flash
    hardware_gpio
    hardware_i2c
    hardware_interp
    hardware_irq
    hardware_pio
    hardware_pll
    hardware_pwm
    hardware_resets
    hardware_spi
    hardware_sync
    hardware_timer
    hardware_uart
    hardware_vreg
    hardware_watchdog
    hardware_xosc
)
if (TARGET pico_cyw43_arch_lwip_poll)
    target_link_libraries(foldcode_sdk_probe pico_cyw43_arch_lwip_poll)
endif()
pico_enable_stdio_usb(foldcode_sdk_probe 1)
pico_enable_stdio_uart(foldcode_sdk_probe 1)
pico_add_extra_outputs(foldcode_sdk_probe)
