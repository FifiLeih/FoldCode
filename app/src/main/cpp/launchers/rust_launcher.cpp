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
    const char* configured = std::getenv("FOLDCODE_RUST_ROOT");
    const std::string native_directory = executable_directory();
    const std::string executable_name = native_directory.empty()
        ? std::string()
        : std::string(argv[0]).substr(std::string(argv[0]).find_last_of('/') + 1);
    const char* configured_tool = std::getenv("FOLDCODE_RUST_TOOL");
    std::string selected_tool;
    if (executable_name.find("foldrustdoc") != std::string::npos) {
        selected_tool = "rustdoc";
    } else if (executable_name.find("foldrustfmt") != std::string::npos) {
        selected_tool = "rustfmt";
    } else if (executable_name.find("foldrustc") != std::string::npos) {
        selected_tool = "rustc";
    } else if (configured_tool != nullptr) {
        selected_tool = configured_tool;
    }
    if (configured == nullptr || *configured == '\0' || selected_tool.empty()) {
        std::fputs("FoldCode Rust: runtime or tool is not configured\n", stderr);
        return 126;
    }
    const std::string root(configured);
    const std::string proot = native_directory + "/libfoldproot.so";
    const std::string loader = native_directory + "/libfoldprootloader.so";
    const std::string rootfs = root + "/rootfs";
    const std::string toolchain = root + "/toolchain";
    const std::string temporary = root + "/tmp";
    const std::string guest_tool = "/opt/rust/bin/" + selected_tool;
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == nullptr) std::strcpy(cwd, "/");
    setenv("PROOT_TMP_DIR", temporary.c_str(), 1);
    setenv("PROOT_LOADER", loader.c_str(), 1);
    // libfoldspawn replaces glibc's clone-based posix_spawn with traceable
    // fork/exec, so PRoot can retain its accelerated seccomp mode.
    unsetenv("PROOT_NO_SECCOMP");
    const char* inherited_path = std::getenv("PATH");
    const std::string guest_path = "/opt/rust/bin:/usr/bin:/bin:" +
        std::string(inherited_path != nullptr ? inherited_path : "");
    setenv("PATH", guest_path.c_str(), 1);
    // rustc dynamically loads its versioned compiler driver from the Rust
    // prefix. The remaining entries provide the small glibc host runtime used
    // by Cargo, rust-analyzer and rustfmt inside the guest.
    setenv("LD_LIBRARY_PATH", "/opt/rust/lib:/usr/lib/aarch64-linux-gnu:/usr/lib", 1);
    setenv("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt", 1);

    std::vector<std::string> values = {
        proot, "-r", rootfs,
        "-b", toolchain + ":/opt/rust",
        "-b", native_directory + ":" + native_directory,
        "-b", "/system:/system",
        "-b", "/apex:/apex",
        "-b", "/vendor:/vendor",
        "-b", "/dev:/dev",
        "-b", "/storage/emulated/0:/storage/emulated/0",
        "-b", "/data/data/dev.foldcode.ide:/data/data/dev.foldcode.ide",
        "-b", "/data/user/0/dev.foldcode.ide:/data/user/0/dev.foldcode.ide",
        "-w", cwd,
        "/usr/bin/foldrust-env",
        guest_tool,
    };
    for (int index = 1; index < argc; ++index) values.emplace_back(argv[index]);
    std::vector<char*> forwarded;
    forwarded.reserve(values.size() + 1);
    for (std::string& value : values) forwarded.push_back(value.data());
    forwarded.push_back(nullptr);
    execv(proot.c_str(), forwarded.data());
    std::fprintf(stderr, "FoldCode Rust: PRoot launch failed: %s\n", std::strerror(errno));
    return 126;
}
