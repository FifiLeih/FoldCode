#include <dlfcn.h>
#include <limits.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <string>

int main(int argc, char** argv) {
    const char* configured = std::getenv("FOLDCODE_CLANGD_CORE");
    if (configured == nullptr || *configured == '\0') {
        std::fputs("FoldCode clangd: FOLDCODE_CLANGD_CORE is not configured\n", stderr);
        return 126;
    }
    void* handle = dlopen(configured, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        std::fprintf(stderr, "FoldCode clangd: dlopen: %s\n", dlerror());
        return 127;
    }
    using Main = int (*)(int, char**);
    dlerror();
    auto* entry = reinterpret_cast<Main>(dlsym(handle, "main"));
    const char* symbol_error = dlerror();
    if (entry == nullptr || symbol_error != nullptr) {
        std::fprintf(stderr, "FoldCode clangd: missing main: %s\n",
                     symbol_error != nullptr ? symbol_error : "unknown error");
        dlclose(handle);
        return 127;
    }
    argv[0] = const_cast<char*>("clangd");
    const int result = entry(argc, argv);
    dlclose(handle);
    return result;
}
