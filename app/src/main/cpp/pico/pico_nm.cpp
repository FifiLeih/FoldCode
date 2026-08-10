#include <elf.h>

#include <charconv>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <string_view>
#include <vector>

namespace {

template <typename T>
bool readValue(const std::vector<std::uint8_t>& bytes, std::size_t offset, T& value) {
    if (offset > bytes.size() || sizeof(T) > bytes.size() - offset) return false;
    std::memcpy(&value, bytes.data() + offset, sizeof(T));
    return true;
}

std::string symbolName(
        const std::vector<std::uint8_t>& bytes,
        std::size_t stringTable,
        std::size_t stringTableSize,
        std::uint32_t offset) {
    if (offset >= stringTableSize || stringTable > bytes.size() ||
        offset > bytes.size() - stringTable) return {};
    const std::size_t start = stringTable + offset;
    const std::size_t limit = stringTable + stringTableSize;
    std::size_t end = start;
    while (end < limit && end < bytes.size() && bytes[end] != 0) ++end;
    if (end == limit || end == bytes.size()) return {};
    return std::string(reinterpret_cast<const char*>(bytes.data() + start), end - start);
}

char symbolType(unsigned char info, std::uint16_t sectionIndex, std::uint64_t sectionFlags) {
    const unsigned char binding = ELF64_ST_BIND(info);
    char type;
    if (sectionIndex == SHN_UNDEF) type = 'U';
    else if (sectionIndex == SHN_ABS) type = 'A';
    else if (binding == STB_WEAK) type = 'W';
    else if ((sectionFlags & SHF_EXECINSTR) != 0) type = 'T';
    else if ((sectionFlags & SHF_WRITE) != 0) type = 'D';
    else type = 'R';
    if (binding == STB_LOCAL && type >= 'A' && type <= 'Z') type = static_cast<char>(type + ('a' - 'A'));
    return type;
}

template <typename Ehdr, typename Shdr, typename Sym>
bool printElfSymbols(const std::vector<std::uint8_t>& bytes, std::string_view label) {
    Ehdr header{};
    if (!readValue(bytes, 0, header) || header.e_shentsize < sizeof(Shdr)) return false;
    if (header.e_shoff > bytes.size()) return false;

    std::vector<Shdr> sections;
    sections.reserve(header.e_shnum);
    for (std::size_t index = 0; index < header.e_shnum; ++index) {
        Shdr section{};
        if (!readValue(bytes, static_cast<std::size_t>(header.e_shoff) + index * header.e_shentsize, section)) {
            return false;
        }
        sections.push_back(section);
    }

    bool found = false;
    for (const Shdr& table : sections) {
        if (table.sh_type != SHT_SYMTAB && table.sh_type != SHT_DYNSYM) continue;
        if (table.sh_link >= sections.size() || table.sh_entsize < sizeof(Sym) || table.sh_entsize == 0) continue;
        const Shdr& strings = sections[table.sh_link];
        if (!label.empty()) std::printf("\n%s:\n", std::string(label).c_str());
        const std::size_t count = static_cast<std::size_t>(table.sh_size / table.sh_entsize);
        for (std::size_t index = 0; index < count; ++index) {
            Sym symbol{};
            if (!readValue(bytes, static_cast<std::size_t>(table.sh_offset) + index * table.sh_entsize, symbol)) {
                return false;
            }
            const std::string name = symbolName(
                    bytes,
                    static_cast<std::size_t>(strings.sh_offset),
                    static_cast<std::size_t>(strings.sh_size),
                    symbol.st_name);
            if (name.empty() || ELF64_ST_TYPE(symbol.st_info) == STT_FILE ||
                ELF64_ST_TYPE(symbol.st_info) == STT_SECTION) continue;
            std::uint64_t flags = 0;
            if (symbol.st_shndx < sections.size()) flags = sections[symbol.st_shndx].sh_flags;
            const char type = symbolType(symbol.st_info, symbol.st_shndx, flags);
            if (symbol.st_shndx == SHN_UNDEF) std::printf("         %c %s\n", type, name.c_str());
            else std::printf("%08llx %c %s\n", static_cast<unsigned long long>(symbol.st_value), type, name.c_str());
            found = true;
        }
    }
    return found;
}

bool printElf(const std::vector<std::uint8_t>& bytes, std::string_view label) {
    if (bytes.size() < EI_NIDENT || std::memcmp(bytes.data(), ELFMAG, SELFMAG) != 0 ||
        bytes[EI_DATA] != ELFDATA2LSB) return false;
    if (bytes[EI_CLASS] == ELFCLASS32) return printElfSymbols<Elf32_Ehdr, Elf32_Shdr, Elf32_Sym>(bytes, label);
    if (bytes[EI_CLASS] == ELFCLASS64) return printElfSymbols<Elf64_Ehdr, Elf64_Shdr, Elf64_Sym>(bytes, label);
    return false;
}

std::string trimArchiveName(std::string name) {
    while (!name.empty() && name.back() == ' ') name.pop_back();
    if (!name.empty() && name.back() == '/') name.pop_back();
    return name;
}

bool printArchive(const std::vector<std::uint8_t>& bytes) {
    constexpr std::string_view magic = "!<arch>\n";
    if (bytes.size() < magic.size() || std::memcmp(bytes.data(), magic.data(), magic.size()) != 0) return false;
    std::size_t offset = magic.size();
    bool found = false;
    while (offset + 60 <= bytes.size()) {
        const char* header = reinterpret_cast<const char*>(bytes.data() + offset);
        if (header[58] != '`' || header[59] != '\n') return false;
        std::size_t memberSize = 0;
        const char* sizeBegin = header + 48;
        const char* sizeEnd = header + 58;
        while (sizeEnd > sizeBegin && sizeEnd[-1] == ' ') --sizeEnd;
        const auto result = std::from_chars(sizeBegin, sizeEnd, memberSize);
        if (result.ec != std::errc()) return false;
        const std::size_t dataOffset = offset + 60;
        if (memberSize > bytes.size() - dataOffset) return false;

        std::string name(header, 16);
        std::size_t payloadOffset = dataOffset;
        std::size_t payloadSize = memberSize;
        if (name.rfind("#1/", 0) == 0) {
            std::size_t nameSize = 0;
            const char* begin = name.data() + 3;
            const char* end = name.data() + name.size();
            while (end > begin && end[-1] == ' ') --end;
            if (std::from_chars(begin, end, nameSize).ec != std::errc() || nameSize > payloadSize) return false;
            name.assign(reinterpret_cast<const char*>(bytes.data() + payloadOffset), nameSize);
            payloadOffset += nameSize;
            payloadSize -= nameSize;
        } else {
            name = trimArchiveName(name);
        }

        // '/', '//', and '/<number>' are archive indexes/name tables, not object files.
        if (!name.empty() && name != "/" && name != "//" && name[0] != '/') {
            std::vector<std::uint8_t> member(bytes.begin() + static_cast<std::ptrdiff_t>(payloadOffset),
                                             bytes.begin() + static_cast<std::ptrdiff_t>(payloadOffset + payloadSize));
            found = printElf(member, name) || found;
        }
        offset = dataOffset + memberSize + (memberSize & 1U);
    }
    return found;
}

bool processFile(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        std::fprintf(stderr, "foldnm: '%s': No such file\n", path.c_str());
        return false;
    }
    std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    if (printArchive(bytes) || printElf(bytes, {})) return true;
    std::fprintf(stderr, "foldnm: '%s': file format not recognized\n", path.c_str());
    return false;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "Usage: llvm-nm <object-or-archive>...\n");
        return 1;
    }
    bool succeeded = true;
    bool hadInput = false;
    for (int index = 1; index < argc; ++index) {
        const std::string_view argument(argv[index]);
        if (argument == "--version") {
            std::puts("FoldCode llvm-nm compatible symbol reader 1.0");
        } else if (!argument.empty() && argument.front() == '-') {
            // Pico SDK only requires the default POSIX nm view. Accept harmless
            // presentation switches used by CMake without changing symbol data.
            continue;
        } else {
            hadInput = true;
            succeeded = processFile(std::string(argument)) && succeeded;
        }
    }
    return hadInput && succeeded ? 0 : (hadInput ? 1 : 0);
}
