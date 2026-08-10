#include <cerrno>
#include <cstdio>
#include <cstring>
#include <limits.h>
#include <string>
#include <unistd.h>
#include <vector>

int main(int argc, char** argv) {
    char executable[PATH_MAX];
    const ssize_t count = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (count <= 0) {
        std::fprintf(stderr, "FoldCode Git: cannot locate native runtime: %s\n", std::strerror(errno));
        return 126;
    }
    executable[count] = '\0';
    std::string directory(executable);
    directory.resize(directory.find_last_of('/'));
    const std::string core = directory + "/libfoldgitcore.so";

    const char* invoked = std::strrchr(argv[0], '/');
    invoked = invoked ? invoked + 1 : argv[0];
    const bool is_service =
        std::strcmp(invoked, "git-receive-pack") == 0 ||
        std::strcmp(invoked, "git-upload-archive") == 0 ||
        std::strcmp(invoked, "git-upload-pack") == 0;

    std::vector<char*> forwarded;
    forwarded.reserve(static_cast<size_t>(argc) + 1);
    forwarded.push_back(const_cast<char*>(is_service ? invoked : "git"));
    for (int index = 1; index < argc; ++index) forwarded.push_back(argv[index]);
    forwarded.push_back(nullptr);
    execv(core.c_str(), forwarded.data());
    std::fprintf(stderr, "FoldCode Git: native Git failed: %s\n", std::strerror(errno));
    return 126;
}
