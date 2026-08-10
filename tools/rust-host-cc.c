typedef unsigned long size_t;
#define NULL ((void *)0)

extern void *calloc(size_t, size_t);
extern int strcmp(const char *, const char *);
extern int strncmp(const char *, const char *, size_t);
extern char *strdup(const char *);
extern char *strchr(const char *, int);
extern int execv(const char *, char *const[]);
extern int unsetenv(const char *);
extern long write(int, const void *, size_t);

/*
 * rustc expects a GCC-compatible host linker for build scripts and procedural
 * macros. The Rust extension already ships LLD, so this tiny driver translates
 * the GCC-style arguments rustc emits and supplies the glibc startup objects.
 */
static void append(char **result, int *used, char *value) {
    result[(*used)++] = value;
}

int main(int argc, char **argv) {
    char **forwarded = calloc((size_t)argc * 8U + 16U, sizeof(char *));
    if (forwarded == NULL) return 125;

    int used = 0;
    append(forwarded, &used,
           "/opt/rust/lib/rustlib/aarch64-unknown-linux-gnu/bin/rust-lld");
    append(forwarded, &used, "-flavor");
    append(forwarded, &used, "gnu");
    int shared = 0;
    for (int index = 1; index < argc; ++index) {
        if (strcmp(argv[index], "-shared") == 0) shared = 1;
    }
    if (!shared) {
        append(forwarded, &used, "/usr/lib/aarch64-linux-gnu/Scrt1.o");
        append(forwarded, &used, "/usr/lib/aarch64-linux-gnu/crti.o");
    }
    append(forwarded, &used, "-L/usr/lib/aarch64-linux-gnu");
    if (!shared) {
        append(forwarded, &used, "--dynamic-linker=/usr/lib/ld-linux-aarch64.so.1");
    }

    for (int index = 1; index < argc; ++index) {
        char *argument = argv[index];
        if (strcmp(argument, "-nodefaultlibs") == 0 ||
            strcmp(argument, "-m64") == 0 ||
            strncmp(argument, "-fuse-ld=", 9) == 0) {
            continue;
        }
        if (strcmp(argument, "-pthread") == 0) {
            append(forwarded, &used, "-lpthread");
            continue;
        }
        if (strcmp(argument, "-rdynamic") == 0) {
            append(forwarded, &used, "--export-dynamic");
            continue;
        }
        if (strncmp(argument, "-Wl,", 4) == 0) {
            char *options = strdup(argument + 4);
            if (options == NULL) return 125;
            char *cursor = options;
            while (cursor != NULL) {
                char *comma = strchr(cursor, ',');
                if (comma != NULL) *comma = '\0';
                if (*cursor != '\0') append(forwarded, &used, cursor);
                cursor = comma == NULL ? NULL : comma + 1;
            }
            continue;
        }
        append(forwarded, &used, argument);
    }

    if (!shared) append(forwarded, &used, "/usr/lib/aarch64-linux-gnu/crtn.o");
    forwarded[used] = NULL;
    // The preload is needed by Cargo and rustc so they can spawn children
    // through PRoot. LLD does not spawn anything; keeping the interposer in
    // its address space can corrupt its allocator under heavy links.
    unsetenv("LD_PRELOAD");
    execv(forwarded[0], forwarded);
    static const char message[] = "FoldCode Rust host linker failed\n";
    write(2, message, sizeof(message) - 1);
    return 127;
}
