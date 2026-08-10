/* Minimal glibc guest bootstrap. PRoot starts this without LD_PRELOAD so the
 * Android host linker is unaffected; it then execs Cargo with the spawn shim
 * in the ordinary guest environment. */
extern int execve(const char *, char *const[], char *const[]);

__attribute__((noreturn)) void fold_main(unsigned long *stack) {
    int argc = (int)stack[0];
    char **argv = (char **)&stack[1];
    char **envp = &argv[argc + 1];
    int count = 0;
    while (envp[count] != 0) ++count;
    char *child_env[count + 2];
    for (int index = 0; index < count; ++index) child_env[index] = envp[index];
    child_env[count] = "LD_PRELOAD=/usr/lib/libfoldspawn.so";
    child_env[count + 1] = 0;
    if (argc > 1) execve(argv[1], &argv[1], child_env);
    __asm__ volatile("mov x0, #127\nmov x8, #93\nsvc #0" ::: "x0", "x8");
    __builtin_unreachable();
}

__asm__(
    ".global _start\n"
    ".type _start,%function\n"
    "_start:\n"
    "mov x0, sp\n"
    "bl fold_main\n"
);
