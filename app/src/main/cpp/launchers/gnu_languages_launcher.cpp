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
    const char* configured = std::getenv("FOLDCODE_GNU_LANGUAGES_ROOT");
    const char* configured_tool = std::getenv("FOLDCODE_GNU_LANGUAGES_TOOL");
    if (configured == nullptr || *configured == '\0' ||
        configured_tool == nullptr || *configured_tool == '\0') {
        std::fputs("FoldCode GNU Languages: runtime or tool is not configured\n", stderr);
        return 126;
    }

    const std::string root(configured);
    const std::string native_directory = executable_directory();
    const std::string proot = native_directory + "/libfoldproot.so";
    const std::string loader = native_directory + "/libfoldprootloader.so";
    const std::string gnu_loader = native_directory + "/libfoldgnuld.so";
    const std::string rootfs = root + "/rootfs";
    const std::string temporary = root + "/tmp";
    const std::string tool(configured_tool);
    std::string guest_tool;
    int first_argument = 1;
    if (tool == "run") {
        if (argc < 2 || argv[1] == nullptr || *argv[1] == '\0') {
            std::fputs("FoldCode GNU Languages: run requires a program path\n", stderr);
            return 126;
        }
        guest_tool = argv[1];
        first_argument = 2;
    } else if (tool == "gfortran" || tool == "cobc" || tool == "cobcrun") {
        guest_tool = "/usr/bin/" + tool;
    } else {
        std::fputs("FoldCode GNU Languages: unsupported tool\n", stderr);
        return 126;
    }

    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == nullptr) std::strcpy(cwd, "/");
    setenv("PROOT_TMP_DIR", temporary.c_str(), 1);
    setenv("PROOT_LOADER", loader.c_str(), 1);
    // Keep PRoot's exec interception enabled. It replaces the guest exec with
    // the APK-owned static loader before Android evaluates the writable
    // extension path. A native bridge that calls execv itself would escape
    // this path and be rejected by Android's app-data execution policy.
    unsetenv("PROOT_NO_SECCOMP");
    setenv("PATH", "/usr/bin:/bin", 1);
    setenv("LD_LIBRARY_PATH", "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib", 1);
    unsetenv("LD_PRELOAD");
    setenv("FOLDCODE_GNU_LOADER", gnu_loader.c_str(), 1);
    setenv("TMPDIR", "/tmp", 1);

    std::vector<std::string> values = {
        proot, "-r", rootfs,
        "-b", native_directory + ":" + native_directory,
        "-b", "/system:/system",
        "-b", "/apex:/apex",
        "-b", "/vendor:/vendor",
        "-b", "/dev:/dev",
        "-b", "/storage/emulated/0:/storage/emulated/0",
        "-b", "/data/data/dev.foldcode.ide:/data/data/dev.foldcode.ide",
        "-b", "/data/user/0/dev.foldcode.ide:/data/user/0/dev.foldcode.ide",
        "-w", cwd,
        // Android permits this loader because it is part of the signed APK.
        // It maps the extension compiler as data, while PRoot remains attached
        // to translate every child process launched by GCC/GnuCOBOL.
        gnu_loader,
        "--library-path", "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib",
        "--preload", "/usr/lib/libfoldspawn.so",
        guest_tool,
    };
    for (int index = first_argument; index < argc; ++index) values.emplace_back(argv[index]);
    std::vector<char*> forwarded;
    forwarded.reserve(values.size() + 1);
    for (std::string& value : values) forwarded.push_back(value.data());
    forwarded.push_back(nullptr);
    execv(proot.c_str(), forwarded.data());
    std::fprintf(stderr, "FoldCode GNU Languages: PRoot launch failed: %s\n", std::strerror(errno));
    return 126;
}
