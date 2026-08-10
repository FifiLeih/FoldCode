#include <jni.h>

#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_foldcode_ide_PicoFullBuildRuntimeImpl_nativeProbe(
        JNIEnv* env,
        jobject /* instance */) {
    const std::string capabilities =
            "Native full-build host ready · ARM64 · CMake driver ABI 1";
    return env->NewStringUTF(capabilities.c_str());
}
