#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits.h>
#include <string>
#include <unistd.h>
#include <vector>

static std::string executable_directory() {
    char path[PATH_MAX];
    const ssize_t size = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (size <= 0) return {};
    path[size] = '\0';
    const std::string executable(path);
    return executable.substr(0, executable.find_last_of('/'));
}

int main(int argc, char** argv) {
    const char* root_value = std::getenv("FOLDCODE_OPENOCD_ROOT");
    const char* executable_value = std::getenv("FOLDCODE_OPENOCD_EXECUTABLE");
    if (root_value == nullptr || executable_value == nullptr) {
        std::fputs("FoldCode OpenOCD: SWD component is not configured\n", stderr);
        return 126;
    }
    const std::string native_directory = executable_directory();
    const std::string proot = native_directory + "/libfoldproot.so";
    const std::string loader = native_directory + "/libfoldprootloader.so";
    setenv("PROOT_LOADER", loader.c_str(), 1);
    setenv("PROOT_TMP_DIR", (std::string(root_value) + "/tmp").c_str(), 1);

    std::vector<std::string> values = {
        proot,
        "-r", "/",
        "-b", "/storage/emulated/0:/storage/emulated/0",
        "-b", "/data/data/dev.foldcode.ide:/data/data/dev.foldcode.ide",
        "-b", "/data/user/0/dev.foldcode.ide:/data/user/0/dev.foldcode.ide",
        executable_value,
    };
    for (int index = 1; index < argc; ++index) values.emplace_back(argv[index]);
    std::vector<char*> forwarded;
    for (std::string& value : values) forwarded.push_back(value.data());
    forwarded.push_back(nullptr);
    execv(proot.c_str(), forwarded.data());
    std::fprintf(stderr, "FoldCode OpenOCD: launch failed: %s\n", std::strerror(errno));
    return 126;
}
