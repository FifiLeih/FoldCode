#include <dlfcn.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

using NodeStart = int (*)(int, char**);

static std::string base_name(const char* path) {
    const char* slash = std::strrchr(path ? path : "", '/');
    return slash ? slash + 1 : (path ? path : "node");
}

int main(int argc, char** argv) {
    const char* configured = std::getenv("FOLDCODE_WEB_ROOT");
    if (!configured || !*configured) {
        std::fputs("FoldCode Node: install the Web Development extension\n", stderr);
        return 126;
    }
    const std::string root(configured);
    const std::string command = base_name(argc > 0 ? argv[0] : "node");
    std::vector<std::string> owned = {"node"};
    if (command == "npm") owned.push_back(root + "/lib/node_modules/npm/bin/npm-cli.js");
    if (command == "npx") owned.push_back(root + "/lib/node_modules/npm/bin/npx-cli.js");
    for (int index = 1; index < argc; ++index) owned.emplace_back(argv[index]);
    std::vector<char*> forwarded;
    forwarded.reserve(owned.size() + 1);
    for (auto& value : owned) forwarded.push_back(value.data());
    forwarded.push_back(nullptr);

    // Current Web extensions deliver an official Android Node executable.
    // Prefer it even if an older extension left libnode.so in the runtime
    // directory; otherwise npm 12 can accidentally run on legacy Node 18.
    const std::string executable = root + "/bin/node";
    if (access(executable.c_str(), R_OK) == 0) {
        owned.insert(owned.begin(), "/system/bin/linker64");
        owned[1] = executable;
        forwarded.clear();
        for (auto& value : owned) forwarded.push_back(value.data());
        forwarded.push_back(nullptr);
        execv(forwarded[0], forwarded.data());
        std::fprintf(stderr, "FoldCode Node: could not launch runtime: %s\n", std::strerror(errno));
        return 126;
    }

    const std::string library = root + "/lib/libnode.so";
    if (access(library.c_str(), R_OK) == 0) {
        void* handle = dlopen(library.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            std::fprintf(stderr, "FoldCode Node: %s\n", dlerror());
            return 126;
        }
        // Node.js Mobile 18 exposes node::Start(int, char**). Keeping the
        // lookup dynamic lets the Android runtime remain extension-delivered.
        auto start = reinterpret_cast<NodeStart>(dlsym(handle, "_ZN4node5StartEiPPc"));
        if (!start) {
            std::fprintf(stderr, "FoldCode Node: node::Start is unavailable: %s\n", dlerror());
            return 126;
        }
        return start(static_cast<int>(owned.size()), forwarded.data());
    }

    std::fputs("FoldCode Node: extension runtime is incomplete\n", stderr);
    return 126;
}
