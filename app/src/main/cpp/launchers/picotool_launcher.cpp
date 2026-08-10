#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
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
    const char* configured = std::getenv("FOLDCODE_PICOTOOL_ROOT");
    if (configured == nullptr || *configured == '\0') {
        std::fputs("FoldCode picotool: extension runtime is not configured\n", stderr);
        return 126;
    }

    const std::string runtime(configured);
    const std::string native_directory = executable_directory();
    const std::string proot = native_directory + "/libfoldproot.so";
    const std::string proot_loader = native_directory + "/libfoldprootloader.so";
    const std::string executable = runtime + "/bin/picotool";
    const std::string temporary = runtime + "/tmp";
    const std::string guest_project = runtime + "/project";
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == nullptr) std::strcpy(cwd, "/");
    const std::string guest_work = runtime + "/work";
    mkdir(guest_work.c_str(), 0700);
    mkdir(guest_project.c_str(), 0700);

    const char* configured_project = std::getenv("FOLDCODE_PROJECT_ROOT");
    const std::string project_root =
        configured_project != nullptr ? configured_project : "";

    setenv("PROOT_LOADER", proot_loader.c_str(), 1);
    setenv("PROOT_TMP_DIR", temporary.c_str(), 1);
    const char* inherited_library_path = std::getenv("LD_LIBRARY_PATH");
    const std::string library_path = runtime + "/lib:" +
        std::string(inherited_library_path != nullptr ? inherited_library_path : "");
    setenv("LD_LIBRARY_PATH", library_path.c_str(), 1);

    std::vector<std::string> values = {
        proot,
        "-r", "/",
        "-b", "/storage/emulated/0:/storage/emulated/0",
        "-b", "/data/data/dev.foldcode.ide:/data/data/dev.foldcode.ide",
        "-b", "/data/user/0/dev.foldcode.ide:/data/user/0/dev.foldcode.ide",
        "-b", std::string(cwd) + ":" + guest_work,
    };
    if (!project_root.empty()) {
        values.emplace_back("-b");
        values.emplace_back(project_root + ":" + guest_project);
    }
    values.emplace_back("-w");
    values.emplace_back(guest_work);
    values.emplace_back(executable);
    const std::string host_work(cwd);
    const std::string host_work_prefix = host_work == "/" ? host_work : host_work + "/";
    const std::string project_prefix = project_root.empty() || project_root == "/"
        ? project_root
        : project_root + "/";
    for (int index = 1; index < argc; ++index) {
        std::string argument(argv[index]);
        // CMake's Pico helpers pass the input ELF as an absolute path on
        // Android shared storage. The extension executable runs inside PRoot,
        // where the current build directory is exposed at guest_work.
        if (argument == host_work) {
            argument = guest_work;
        } else if (argument.rfind(host_work_prefix, 0) == 0) {
            argument = guest_work + "/" + argument.substr(host_work_prefix.size());
        } else if (!project_root.empty() && argument == project_root) {
            argument = guest_project;
        } else if (!project_prefix.empty() && argument.rfind(project_prefix, 0) == 0) {
            argument = guest_project + "/" + argument.substr(project_prefix.size());
        }
        values.emplace_back(std::move(argument));
    }
    std::vector<char*> forwarded;
    forwarded.reserve(values.size() + 1);
    for (std::string& value : values) forwarded.push_back(value.data());
    forwarded.push_back(nullptr);
    execv(proot.c_str(), forwarded.data());
    std::fprintf(stderr, "FoldCode picotool: PRoot launch failed: %s\n", std::strerror(errno));
    return 126;
}
