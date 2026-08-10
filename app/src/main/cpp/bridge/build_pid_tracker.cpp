#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <unistd.h>

namespace {

void record_pid(char event) {
    const char* path = std::getenv("FOLDCODE_BUILD_PID_FILE");
    if (path == nullptr || *path == '\0') return;
    const int descriptor = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (descriptor < 0) return;
    char record[32];
    const int length = std::snprintf(record, sizeof(record), "%c%d\n", event, getpid());
    if (length > 0 && length < static_cast<int>(sizeof(record))) {
        ssize_t written = 0;
        while (written < length) {
            const ssize_t count = write(descriptor, record + written, length - written);
            if (count > 0) {
                written += count;
            } else if (count < 0 && errno == EINTR) {
                continue;
            } else {
                break;
            }
        }
    }
    close(descriptor);
}

__attribute__((constructor)) void register_build_process() {
    record_pid('+');
}

__attribute__((destructor)) void unregister_build_process() {
    record_pid('-');
}

}  // namespace
