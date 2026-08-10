#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
uint32_t reverse_bits(uint32_t value, int width) {
    uint32_t result = 0;
    for (int bit = 0; bit < width; ++bit) {
        result = (result << 1U) | ((value >> bit) & 1U);
    }
    return result;
}

uint32_t bootrom_crc(const std::vector<uint8_t>& data, uint32_t seed) {
    uint32_t crc = seed;
    for (uint8_t byte : data) {
        crc ^= reverse_bits(byte, 8);
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) ? 0xedb88320U : 0U);
        }
    }
    return reverse_bits(crc, 32);
}

uint32_t parse_number(const char* text) {
    char* end = nullptr;
    const unsigned long value = std::strtoul(text, &end, 0);
    if (end == text || *end != '\0') throw std::runtime_error("invalid numeric argument");
    return static_cast<uint32_t>(value);
}
}

int main(int argc, char** argv) {
    try {
        if (argc < 4) throw std::runtime_error("usage: foldpicopad SCRIPT [options] INPUT OUTPUT");
        const bool checksum = std::string(argv[1]).find("pad_checksum") != std::string::npos;
        size_t padded_size = 256;
        uint32_t seed = 0;
        std::vector<std::string> positional;
        for (int index = 2; index < argc; ++index) {
            const std::string argument = argv[index];
            if ((argument == "-p" || argument == "--pad") && index + 1 < argc) {
                padded_size = parse_number(argv[++index]);
            } else if ((argument == "-s" || argument == "--seed") && index + 1 < argc) {
                seed = parse_number(argv[++index]);
            } else {
                positional.push_back(argument);
            }
        }
        if (positional.size() != 2) throw std::runtime_error("input and output paths are required");
        std::ifstream input(positional[0], std::ios::binary);
        if (!input) throw std::runtime_error("could not open input file");
        std::vector<uint8_t> data((std::istreambuf_iterator<char>(input)), {});
        const size_t payload_size = padded_size - (checksum ? 4U : 0U);
        if (data.size() > payload_size) throw std::runtime_error("boot stage is larger than padded size");
        data.resize(payload_size, 0);
        if (checksum) {
            const uint32_t crc = bootrom_crc(data, seed);
            for (int shift = 0; shift < 32; shift += 8) data.push_back(static_cast<uint8_t>(crc >> shift));
        }
        std::ofstream output(positional[1]);
        if (!output) throw std::runtime_error("could not open output file");
        output << (checksum ? "// Padded and checksummed version of: " : "// Padded version of: ")
               << positional[0] << "\n\n";
        if (checksum) output << ".cpu cortex-m0plus\n.thumb\n\n";
        output << ".section .boot2, \"ax\"\n\n";
        if (!checksum) output << ".global __boot2_entry_point\n__boot2_entry_point:\n";
        output << std::hex << std::setfill('0');
        for (size_t offset = 0; offset < data.size(); offset += 16) {
            output << ".byte ";
            for (size_t index = offset; index < data.size() && index < offset + 16; ++index) {
                if (index != offset) output << ", ";
                output << "0x" << std::setw(2) << static_cast<unsigned>(data[index]);
            }
            output << '\n';
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "foldpicopad: " << error.what() << '\n';
        return 1;
    }
}
