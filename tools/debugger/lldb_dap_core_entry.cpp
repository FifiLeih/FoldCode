// Entry point exported by the extension-delivered LLDB DAP shared core.
// lldb-dap.cpp is compiled with main renamed to foldcode_lldb_dap_main; the
// APK-owned launcher loads this library and invokes this stable C ABI symbol.
#ifdef main
#undef main
#endif

extern int foldcode_lldb_dap_main(int argc, char **argv);

extern "C" __attribute__((visibility("default")))
int main(int argc, char **argv) {
    return foldcode_lldb_dap_main(argc, argv);
}
