#include <dlfcn.h>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using PythonMain = int (*)(int, char**);

static void load_optional(const std::string& path) {
    dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
}

static const char* base_name(const char* path) {
    const char* slash = std::strrchr(path, '/');
    return slash == nullptr ? path : slash + 1;
}

static bool starts_with(const std::string& value, const char* prefix) {
    return value.rfind(prefix, 0) == 0;
}

static bool has_explicit_install_destination(
    const std::vector<std::string>& arguments,
    size_t first_option
) {
    for (size_t index = first_option; index < arguments.size(); ++index) {
        const std::string& argument = arguments[index];
        if (
            argument == "--target" || argument == "-t" ||
            argument == "--user" || argument == "--prefix" || argument == "--root" ||
            starts_with(argument, "--target=") || starts_with(argument, "--prefix=") ||
            starts_with(argument, "--root=") ||
            (starts_with(argument, "-t") && argument.size() > 2)
        ) {
            return true;
        }
    }
    return false;
}

static void add_project_install_target(
    std::vector<std::string>& arguments,
    size_t command_index
) {
    if (
        command_index >= arguments.size() || arguments[command_index] != "install" ||
        has_explicit_install_destination(arguments, command_index + 1)
    ) {
        return;
    }
    const char* project_root = std::getenv("FOLDCODE_PROJECT_ROOT");
    if (project_root == nullptr || project_root[0] == '\0') return;
    const std::string target = std::string(project_root) + "/.foldcode/python";
    arguments.insert(arguments.begin() + static_cast<std::ptrdiff_t>(command_index + 1), target);
    arguments.insert(arguments.begin() + static_cast<std::ptrdiff_t>(command_index + 1), "--target");
}

static int run_python(PythonMain python_main, std::vector<std::string>& arguments) {
    std::vector<char*> adapted;
    adapted.reserve(arguments.size() + 1);
    for (std::string& argument : arguments) adapted.push_back(argument.data());
    adapted.push_back(nullptr);
    return python_main(static_cast<int>(arguments.size()), adapted.data());
}

int main(int argc, char** argv) {
    const char* configured = std::getenv("FOLDCODE_PYTHON_ROOT");
    if (configured == nullptr || configured[0] == '\0') {
        std::fprintf(stderr, "FoldCode Python: FOLDCODE_PYTHON_ROOT is not configured\n");
        return 127;
    }
    const std::string root(configured);
    const std::string prefix = root + "/prefix";
    const std::string library = prefix + "/lib";
    // The Android runtime ships OpenSSL's modern default provider but not the
    // optional legacy module (RC2, RC4, Blowfish, IDEA, SEED). cryptography's
    // supported opt-out prevents a misleading warning on every import while
    // preserving an explicit user override.
    setenv("CRYPTOGRAPHY_OPENSSL_NO_LEGACY", "1", 0);
    setenv("PYTHONHOME", prefix.c_str(), 1);
    std::string python_path = library + "/python3.14:" + library + "/python3.14/lib-dynload";
    const char* inherited_python_path = std::getenv("PYTHONPATH");
    if (inherited_python_path != nullptr && inherited_python_path[0] != '\0') {
        python_path += ":";
        python_path += inherited_python_path;
    }
    setenv("PYTHONPATH", python_path.c_str(), 1);
    load_optional(library + "/libcrypto_python.so");
    load_optional(library + "/libssl_python.so");
    load_optional(library + "/libsqlite3_python.so");
    void* handle = dlopen((library + "/libpython3.14.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        std::fprintf(stderr, "FoldCode Python: %s\n", dlerror());
        return 127;
    }
    auto python_main = reinterpret_cast<PythonMain>(dlsym(handle, "Py_BytesMain"));
    if (python_main == nullptr) {
        std::fprintf(stderr, "FoldCode Python: Py_BytesMain is unavailable: %s\n", dlerror());
        return 127;
    }
    const std::string invoked = argc > 0 ? base_name(argv[0]) : "python";
    const bool direct_pip = invoked == "pip" || invoked == "pip3";
    const bool module_pip = argc >= 3 && std::strcmp(argv[1], "-m") == 0 &&
        std::strcmp(argv[2], "pip") == 0;
    if (direct_pip || module_pip) {
        std::vector<std::string> arguments;
        if (direct_pip) {
            arguments = {"python", "-m", "pip"};
            for (int index = 1; index < argc; ++index) arguments.emplace_back(argv[index]);
        } else {
            arguments.reserve(static_cast<size_t>(argc));
            for (int index = 0; index < argc; ++index) arguments.emplace_back(argv[index]);
        }
        const size_t command_index = 3;
        add_project_install_target(arguments, command_index);
        return run_python(python_main, arguments);
    }
    return python_main(argc, argv);
}
