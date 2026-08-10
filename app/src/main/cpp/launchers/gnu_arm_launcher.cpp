#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <libgen.h>
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
    const char* configured = std::getenv("FOLDCODE_GNU_ARM_ROOT");
    if (configured == nullptr || *configured == '\0') {
        std::fputs("FoldCode GNU Arm: runtime is not configured\n", stderr);
        return 126;
    }
    const std::string root(configured);
    const std::string native_directory = executable_directory();
    const std::string proot = native_directory + "/libfoldproot.so";
    const std::string rootfs = root + "/rootfs";
    const std::string toolchain = root + "/toolchain";
    const std::string temporary = root + "/tmp";
    const std::string tool = basename(argv[0]);
    const std::string guest_tool = "/opt/gcc/bin/" + tool;
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == nullptr) std::strcpy(cwd, "/");
    setenv("PROOT_TMP_DIR", temporary.c_str(), 1);
    // Android forbids an ordinary app from executing code extracted into its
    // writable data directory. Use the PRoot loader shipped in the APK's
    // executable native-library directory; the large GNU payload stays remote.
    const std::string proot_loader = native_directory + "/libfoldprootloader.so";
    setenv("PROOT_LOADER", proot_loader.c_str(), 1);
    const char* inherited_path = std::getenv("PATH");
    const std::string guest_path = "/opt/gcc/bin:" + std::string(inherited_path != nullptr ? inherited_path : "/usr/bin:/bin");
    setenv("PATH", guest_path.c_str(), 1);

    std::vector<std::string> values = {
        proot, "-r", rootfs,
        "-b", toolchain + ":/opt/gcc",
        "-b", "/storage/emulated/0:/storage/emulated/0",
        "-b", "/data/data/dev.foldcode.ide:/data/data/dev.foldcode.ide",
        "-b", "/data/user/0/dev.foldcode.ide:/data/user/0/dev.foldcode.ide",
        "-w", cwd,
        guest_tool,
    };
    for (int index = 1; index < argc; ++index) values.emplace_back(argv[index]);
    std::vector<char*> forwarded;
    forwarded.reserve(values.size() + 1);
    for (std::string& value : values) forwarded.push_back(value.data());
    forwarded.push_back(nullptr);
    execv(proot.c_str(), forwarded.data());
    std::fprintf(stderr, "FoldCode GNU Arm: PRoot launch failed: %s\n", std::strerror(errno));
    return 126;
}
