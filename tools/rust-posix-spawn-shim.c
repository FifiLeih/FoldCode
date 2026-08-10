/* Cargo's glibc posix_spawn clone path is not observable by PRoot on some
 * Android kernels. This freestanding preload restores traceable fork/exec. */
typedef int pid_t;
typedef unsigned int mode_t;

struct fold_spawn_action {
    enum {
        FOLD_SPAWN_CLOSE,
        FOLD_SPAWN_DUP2,
        FOLD_SPAWN_OPEN,
        FOLD_SPAWN_CHDIR,
        FOLD_SPAWN_FCHDIR,
        FOLD_SPAWN_CLOSEFROM,
        FOLD_SPAWN_TCSETPGRP,
    } tag;
    union {
        struct { int fd; } close_action;
        struct { int fd; int newfd; } dup2_action;
        struct { int fd; char *path; int oflag; mode_t mode; } open_action;
        struct { char *path; } chdir_action;
        struct { int fd; } fchdir_action;
        struct { int from; } closefrom_action;
        struct { int fd; } tcsetpgrp_action;
    } action;
};

typedef struct {
    int allocated;
    int used;
    struct fold_spawn_action *actions;
    int pad[16];
} fold_spawn_file_actions;

extern pid_t fork(void);
extern int close(int);
extern int dup2(int, int);
extern int open(const char *, int, ...);
extern int chdir(const char *);
extern int fchdir(int);
extern int execvpe(const char *, char *const[], char *const[]);
extern int access(const char *, int);
extern pid_t waitpid(pid_t, int *, int);
extern pid_t getpid(void);
extern long write(int, const void *, unsigned long);
extern char **environ;
extern void _exit(int);
extern int *__errno_location(void);
extern long syscall(long, ...);

static void apply_actions(const fold_spawn_file_actions *actions) {
    if (actions == 0) return;
    for (int index = 0; index < actions->used; ++index) {
        const struct fold_spawn_action *item = &actions->actions[index];
        switch (item->tag) {
            case FOLD_SPAWN_CLOSE:
                close(item->action.close_action.fd);
                break;
            case FOLD_SPAWN_DUP2:
                dup2(item->action.dup2_action.fd, item->action.dup2_action.newfd);
                break;
            case FOLD_SPAWN_OPEN: {
                int fd = open(item->action.open_action.path,
                              item->action.open_action.oflag,
                              item->action.open_action.mode);
                if (fd >= 0 && fd != item->action.open_action.fd) {
                    dup2(fd, item->action.open_action.fd);
                    close(fd);
                }
                break;
            }
            case FOLD_SPAWN_CHDIR:
                chdir(item->action.chdir_action.path);
                break;
            case FOLD_SPAWN_FCHDIR:
                fchdir(item->action.fchdir_action.fd);
                break;
            default:
                break;
        }
    }
}

static int starts_with(const char *value, const char *prefix) {
    while (*prefix != 0) {
        if (*value++ != *prefix++) return 0;
    }
    return 1;
}

static const char *environment_value(char *const envp[], const char *name) {
    if (envp == 0) return 0;
    for (int index = 0; envp[index] != 0; ++index) {
        if (starts_with(envp[index], name)) {
            const char *value = envp[index];
            while (*value != '=') ++value;
            return value + 1;
        }
    }
    return 0;
}

/* Publish each live glibc guest process to an app-private build session.
 * O_APPEND keeps the short records atomic when Cargo starts several helpers
 * close together. The Android host later queries PSS for these same-UID PIDs. */
static void record_build_pid(char event) {
    const char *path = environment_value(environ, "FOLDCODE_BUILD_PID_FILE=");
    if (path == 0 || *path == 0) return;
    const int descriptor = open(path, 1 | 64 | 1024, 0600); /* WRONLY|CREAT|APPEND */
    if (descriptor < 0) return;
    unsigned int value = (unsigned int)getpid();
    char reversed[16];
    int digits = 0;
    do {
        reversed[digits++] = (char)('0' + value % 10);
        value /= 10;
    } while (value != 0 && digits < (int)sizeof(reversed));
    char record[20];
    int length = 0;
    record[length++] = event;
    while (digits > 0) record[length++] = reversed[--digits];
    record[length++] = '\n';
    (void)write(descriptor, record, (unsigned long)length);
    close(descriptor);
}

__attribute__((constructor)) static void register_build_pid(void) {
    record_build_pid('+');
}

__attribute__((destructor)) static void unregister_build_pid(void) {
    record_build_pid('-');
}

static int contains_slash(const char *value) {
    while (*value != 0) if (*value++ == '/') return 1;
    return 0;
}

static const char *resolve_from_path(const char *file, char *const envp[], char candidate[4096]) {
    if (contains_slash(file)) return file;
    const char *path = environment_value(envp, "PATH=");
    if (path == 0 || *path == 0) path = "/usr/bin:/bin";
    while (*path != 0) {
        int length = 0;
        while (path[length] != 0 && path[length] != ':') ++length;
        int offset = 0;
        if (length == 0) candidate[offset++] = '.';
        else for (int index = 0; index < length && offset < 4093; ++index) candidate[offset++] = path[index];
        candidate[offset++] = '/';
        for (int index = 0; file[index] != 0 && offset < 4095; ++index) candidate[offset++] = file[index];
        candidate[offset] = 0;
        if (access(candidate, 1) == 0) return candidate; /* X_OK */
        path += length;
        if (*path == ':') ++path;
    }
    return file;
}

static void exec_child(const char *file, int search_path, char *const argv[], char *const envp[]) {
    const char *loader = environment_value(envp, "FOLDCODE_GNU_LOADER=");
    if (loader == 0 || *loader == 0) {
        if (search_path) execvpe(file, argv, envp);
        else syscall(221, file, argv, envp); /* __NR_execve on AArch64 */
        return;
    }
    char candidate[4096];
    const char *program = search_path ? resolve_from_path(file, envp, candidate) : file;
    int count = 0;
    while (argv[count] != 0) ++count;
    char *wrapped[count + 6];
    wrapped[0] = (char *)loader;
    wrapped[1] = "--library-path";
    wrapped[2] = "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib";
    wrapped[3] = "--preload";
    wrapped[4] = "/usr/lib/libfoldspawn.so";
    wrapped[5] = (char *)program;
    for (int index = 1; index < count; ++index) wrapped[index + 5] = argv[index];
    wrapped[count + 5] = 0;
    syscall(221, loader, wrapped, envp); /* __NR_execve on AArch64 */
}

static int parse_simple_command(const char *command, char *buffer, char **arguments, int capacity) {
    const char *source = command;
    char *target = buffer;
    int count = 0;
    while (*source != 0) {
        while (*source == ' ' || *source == '\t' || *source == '\n') ++source;
        if (*source == 0) break;
        if (count + 1 >= capacity) return -1;
        arguments[count++] = target;
        char quote = 0;
        while (*source != 0) {
            char value = *source++;
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                    continue;
                }
                if (value == '\\' && quote == '"' && *source != 0) value = *source++;
                *target++ = value;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                continue;
            }
            if (value == '\\' && *source != 0) {
                *target++ = *source++;
                continue;
            }
            if (value == ' ' || value == '\t' || value == '\n') break;
            /* These require real shell semantics. */
            if (value == ';' || value == '|' || value == '&' || value == '<' ||
                value == '>' || value == '`' || value == '$') return -1;
            *target++ = value;
        }
        if (quote != 0) return -1;
        *target++ = 0;
    }
    arguments[count] = 0;
    return count;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    exec_child(path, 0, argv, envp);
    return -1;
}

/* GCC's pexecute implementation calls execv/execvp directly for cc1,
 * assembler and linker subprocesses. glibc resolves those internally rather
 * than through our execve interposer, so wrap both public entry points too. */
int execv(const char *path, char *const argv[]) {
    exec_child(path, 0, argv, environ);
    return -1;
}

int execvp(const char *file, char *const argv[]) {
    exec_child(file, 1, argv, environ);
    return -1;
}

int system(const char *command) {
    if (command == 0) return 1;
    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        unsigned long length = 0;
        while (command[length] != 0) ++length;
        char buffer[length + 1];
        char *arguments[257];
        const int count = parse_simple_command(command, buffer, arguments, 257);
        if (count > 0) {
            /* cobc emits plain compiler/linker commands. Avoiding /bin/sh here
             * keeps every executable transition visible to PRoot on Android. */
            exec_child(arguments[0], 1, arguments, environ);
        } else {
            char *shell_arguments[] = { "sh", "-c", (char *)command, 0 };
            syscall(221, "/bin/sh", shell_arguments, environ); /* __NR_execve */
        }
        _exit(127);
    }
    int status = 0;
    while (waitpid(child, &status, 0) < 0) {
        if (*__errno_location() != 4) return -1; /* EINTR */
    }
    return status;
}

int posix_spawnp(pid_t *pid,
                 const char *file,
                 const fold_spawn_file_actions *file_actions,
                 const void *attributes,
                 char *const argv[],
                 char *const envp[]) {
    (void)attributes;
    pid_t child = fork();
    if (child < 0) return *__errno_location();
    if (child == 0) {
        apply_actions(file_actions);
        int count = 0;
        while (envp != 0 && envp[count] != 0) ++count;
        char *child_env[count + 2];
        for (int index = 0; index < count; ++index) child_env[index] = envp[index];
        child_env[count] = "LD_PRELOAD=/usr/lib/libfoldspawn.so";
        child_env[count + 1] = 0;
        exec_child(file, 1, argv, child_env);
        _exit(127);
    }
    if (pid != 0) *pid = child;
    return 0;
}

int posix_spawn(pid_t *pid,
                const char *path,
                const fold_spawn_file_actions *file_actions,
                const void *attributes,
                char *const argv[],
                char *const envp[]) {
    (void)attributes;
    pid_t child = fork();
    if (child < 0) return *__errno_location();
    if (child == 0) {
        apply_actions(file_actions);
        int count = 0;
        while (envp != 0 && envp[count] != 0) ++count;
        char *child_env[count + 2];
        for (int index = 0; index < count; ++index) child_env[index] = envp[index];
        child_env[count] = "LD_PRELOAD=/usr/lib/libfoldspawn.so";
        child_env[count + 1] = 0;
        exec_child(path, 0, argv, child_env);
        _exit(127);
    }
    if (pid != 0) *pid = child;
    return 0;
}

/* glibc 2.41 exposes pidfd_spawnp and Rust prefers it when available. Return
 * the same kernel pidfd that glibc would have supplied. */
int pidfd_spawnp(int *pidfd,
                 const char *file,
                 const fold_spawn_file_actions *file_actions,
                 const void *attributes,
                 char *const argv[],
                 char *const envp[]) {
    pid_t pid = -1;
    int result = posix_spawnp(&pid, file, file_actions, attributes, argv, envp);
    if (result != 0) return result;
    long descriptor = syscall(434, pid, 0); /* __NR_pidfd_open on AArch64 */
    if (descriptor < 0) return *__errno_location();
    *pidfd = (int)descriptor;
    return 0;
}
