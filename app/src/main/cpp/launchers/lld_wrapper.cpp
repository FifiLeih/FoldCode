#include <limits.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

int main(int argc, char** argv) {
    char executable_path[PATH_MAX]{};
    const ssize_t length = readlink("/proc/self/exe", executable_path, sizeof(executable_path) - 1);
    if (length <= 0) {
        std::perror("FoldCode linker: readlink");
        return 127;
    }

    std::string directory(executable_path, static_cast<size_t>(length));
    const size_t slash = directory.find_last_of('/');
    if (slash == std::string::npos) {
        std::fputs("FoldCode linker: invalid executable path\n", stderr);
        return 127;
    }
    directory.resize(slash + 1);
    const char* configured_core = std::getenv("FOLDCODE_LLD_CORE");
    const std::string core = configured_core != nullptr && configured_core[0] != '\0'
        ? configured_core
        : directory + "libfoldlldcore.so";

    std::vector<char*> forwarded;
    forwarded.reserve(static_cast<size_t>(argc) + 1);
    forwarded.push_back(const_cast<char*>("ld.lld"));
    for (int index = 1; index < argc; ++index) forwarded.push_back(argv[index]);
    forwarded.push_back(nullptr);

    void* handle = dlopen(core.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        std::fprintf(stderr, "FoldCode linker: dlopen: %s\n", dlerror());
        return 127;
    }
    using Main = int (*)(int, char**);
    dlerror();
    auto* entry = reinterpret_cast<Main>(dlsym(handle, "main"));
    const char* symbol_error = dlerror();
    if (symbol_error != nullptr || entry == nullptr) {
        std::fprintf(stderr, "FoldCode linker: dlsym(main): %s\n",
                     symbol_error != nullptr ? symbol_error : "missing symbol");
        dlclose(handle);
        return 127;
    }
    const int result = entry(argc, forwarded.data());
    dlclose(handle);
    return result;
}
