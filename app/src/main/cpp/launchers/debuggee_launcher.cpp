#include <dlfcn.h>
#include <unistd.h>
#include <csignal>

#include <cstdlib>
#include <cstdio>
#include <vector>

// APK-owned trampoline for debugger-launched programs. Debug builds are shared
// modules so this trusted executable can load their symbols before suspending.
// LLDB can consequently bind source breakpoints before calling user main().
int main(int argc, char **argv) {
    if (argc < 2) {
        std::fputs("FoldCode debugger: missing program path\n", stderr);
        return 64;
    }
    void *module = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (module == nullptr) {
        std::fprintf(stderr, "FoldCode debugger: dlopen: %s\n", dlerror());
        return 126;
    }
    dlerror();
    void *symbol = dlsym(module, "main");
    const char *symbol_error = dlerror();
    if (symbol_error != nullptr || symbol == nullptr) {
        std::fprintf(stderr, "FoldCode debugger: main: %s\n", symbol_error == nullptr ? "symbol missing" : symbol_error);
        return 126;
    }

    const char *debug_wait = std::getenv("FOLDCODE_DEBUG_WAIT");
    if (debug_wait != nullptr && *debug_wait != '\0') {
        std::printf("FOLDCODE_DEBUG_PID %d\n", getpid());
        std::fflush(stdout);
        raise(SIGSTOP);
    }
    std::vector<char *> forwarded;
    forwarded.reserve(static_cast<size_t>(argc));
    for (int index = 1; index < argc; ++index) forwarded.push_back(argv[index]);
    forwarded.push_back(nullptr);
    using MainFunction = int (*)(int, char **);
    const auto user_main = reinterpret_cast<MainFunction>(symbol);
    return user_main(argc - 1, forwarded.data());
}
