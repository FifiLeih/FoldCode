import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val debugExtensionCatalogUrl = providers.gradleProperty("foldcodeExtensionCatalogUrl")
    .orElse(localProperties.getProperty("foldcodeExtensionCatalogUrl", ""))
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val releaseStorePath = providers.environmentVariable("FOLDCODE_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("FOLDCODE_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("FOLDCODE_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("FOLDCODE_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val baseNativeDirectory = rootProject.file(
    providers.gradleProperty("foldcodeBaseNativeDir")
        .orElse(localProperties.getProperty("foldcodeBaseNativeDir", "app/src/main/jniLibs/arm64-v8a"))
        .get(),
)
val requiredBaseNativeFiles = listOf(
    "libc++_shared.so",
    "libfoldar.so",
    "libfoldclang.so",
    "libfoldcmake.so",
    "libfoldgitcore.so",
    "libfoldgitremotehttp.so",
    "libfoldgnuld.so",
    "libfoldld.so",
    "libfoldninja.so",
    "libfoldobjcopy.so",
    "libfoldobjdump.so",
    "libfoldproot.so",
    "libfoldprootloader.so",
    "libfoldranlib.so",
    "libfoldriscvar.so",
    "libfoldriscvobjcopy.so",
    "libfoldriscvobjdump.so",
    "libfoldriscvranlib.so",
)
val androidSdkDirectory = file(
    localProperties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: error("Android SDK path is not configured")
)
val ndkHostPrebuiltDirectory = when {
    System.getProperty("os.name").contains("Mac", ignoreCase = true) -> "darwin-x86_64"
    System.getProperty("os.name").contains("Linux", ignoreCase = true) -> "linux-x86_64"
    System.getProperty("os.name").contains("Windows", ignoreCase = true) -> "windows-x86_64"
    else -> error("Unsupported Android NDK host: ${System.getProperty("os.name")}")
}
val packagedLldbServerSource = providers.gradleProperty("foldcodeLldbServer")
    .orNull
    ?.let(::file)
    ?: listOf("30.0.14904198", "27.2.12479018")
        .map { version ->
            androidSdkDirectory.resolve(
                "ndk/$version/toolchains/llvm/prebuilt/$ndkHostPrebuiltDirectory/lib/clang/" +
                    (if (version.startsWith("30.")) "21" else "18") +
                    "/lib/linux/aarch64/lldb-server"
            )
        }
        .firstOrNull(File::isFile)
    ?: error("No Android ARM64 lldb-server was found in the configured NDKs")
val generatedLldbServerDirectory = layout.buildDirectory.dir("generated/lldb-server/jniLibs")
val generatedLegalAssetsDirectory = layout.buildDirectory.dir("generated/legal-assets")
val generatedBaseAssetsDirectory = layout.buildDirectory.dir("generated/base-assets")
val generatedBaseNativeDirectory = layout.buildDirectory.dir("generated/base-native/jniLibs")
val prepareLldbServer by tasks.registering(Copy::class) {
    from(packagedLldbServerSource)
    into(generatedLldbServerDirectory.map { it.dir("arm64-v8a") })
    rename { "foldlldbserver.so" }
}
val prepareLegalNotices by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(generatedLegalAssetsDirectory.map { it.dir("legal") })
}
val prepareBaseAssets by tasks.registering(Sync::class) {
    from("src/main/assets") {
        include("git/cacert.pem", "templates/pico_sdk_import.cmake")
    }
    into(generatedBaseAssetsDirectory)
}
val verifyBaseNativeRuntime by tasks.registering {
    group = "verification"
    description = "Fails before packaging when the external ARM64 base runtime is incomplete."
    inputs.files(requiredBaseNativeFiles.map(baseNativeDirectory::resolve))
    doLast {
        val missing = requiredBaseNativeFiles.filterNot { baseNativeDirectory.resolve(it).isFile }
        check(missing.isEmpty()) {
            "Missing FoldCode base runtime files in ${baseNativeDirectory.absolutePath}: " +
                missing.joinToString() +
                ". Install a verified base-native bundle or set foldcodeBaseNativeDir."
        }
    }
}
val prepareBaseNativeRuntime by tasks.registering(Sync::class) {
    dependsOn(verifyBaseNativeRuntime)
    from(baseNativeDirectory) {
        include(requiredBaseNativeFiles)
    }
    into(generatedBaseNativeDirectory.map { it.dir("arm64-v8a") })
}

android {
    namespace = "dev.foldcode.ide"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.foldcode.ide"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Public releases use local .fcex installation. A catalog is a debug-only
        // development convenience until a signed distribution service exists.
        buildConfigField("String", "EXTENSION_CATALOG_URL", "\"\"")
        manifestPlaceholders["usesCleartextTraffic"] = "false"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra")
            }
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("production") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            // Local development registry traffic is allowed only in debug APKs.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            buildConfigField("String", "EXTENSION_CATALOG_URL", "\"$debugExtensionCatalogUrl\"")
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("production")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.pickFirsts += "OSGI-INF/l10n/plugin.properties"
        resources.pickFirsts += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.excludes += "META-INF/versions/**/OSGI-INF/**"
        // FoldCode starts as an editor. Only small Android-labelled launcher stubs
        // stay in the APK; compiler dependency libraries arrive in local .fcex files.
        jniLibs.excludes += setOf(
            "**/libLLVM.so",
            "**/libclang-cpp.so",
            "**/libfoldlldcore.so",
            "**/libicudataxxx.so",
            "**/libicuucxxx.so",
            "**/libxml2xxx.so",
            "**/libiconv.so",
            "**/libzstdxx.so",
            "**/libffi.so",
            "**/libzzz.so",
        )
    }

    sourceSets.named("main") {
        // The sysroot/resource directory is part of the locally installed C/C++ payload.
        // Keep only the small editor grammars/themes in the base APK.
        assets.setSrcDirs(listOf("src/main/editorAssets"))
        assets.srcDir(generatedBaseAssetsDirectory)
        assets.srcDir(generatedLegalAssetsDirectory)
        // Android blocks execution from writable extension storage. Keep only
        // the small local process server in the trusted APK; LLDB, DAP and all
        // compiler libraries remain independently delivered by local extensions.
        jniLibs.setSrcDirs(listOf(generatedBaseNativeDirectory))
        jniLibs.srcDir(generatedLldbServerDirectory)
    }

    lint {
        // FoldCode intentionally starts ARM64-only; x86_64 is not a supported runner target.
        disable += "ChromeOsAbiSupport"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild").configure {
    dependsOn(
        prepareLldbServer,
        prepareLegalNotices,
        prepareBaseAssets,
    )
}

// JVM tests and lint do not execute packaged Android binaries, so they remain
// runnable in a source-only checkout. APK assembly still fails closed unless
// the reviewed base-native contributor bundle has been installed.
tasks.matching {
    it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders"
}.configureEach {
    dependsOn(prepareBaseNativeRuntime)
}

val verifyReleaseApkContents by tasks.registering {
    group = "verification"
    description = "Inspects release APK contents for forbidden local runtime inputs."
    dependsOn("assembleRelease")
    doLast {
        val releaseDirectory = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apks = releaseDirectory.listFiles { file -> file.isFile && file.extension == "apk" }
            ?.toList()
            .orEmpty()
        check(apks.isNotEmpty()) { "No release APK was produced in ${releaseDirectory.absolutePath}" }

        val forbidden = setOf(
            "assets/toolchain.zip",
            "lib/arm64-v8a/libLLVM.so",
            "lib/arm64-v8a/libclang-cpp.so",
            "lib/arm64-v8a/libfoldlldcore.so",
            "lib/arm64-v8a/libicudataxxx.so",
        )
        val required = setOf(
            "assets/git/cacert.pem",
            "assets/templates/pico_sdk_import.cmake",
        ) + requiredBaseNativeFiles.map { "lib/arm64-v8a/$it" }
        apks.forEach { apk ->
            ZipFile(apk).use { archive ->
                val entries = archive.entries().asSequence().map { it.name }.toSet()
                val leaked = forbidden.intersect(entries)
                check(leaked.isEmpty()) {
                    "Forbidden release inputs in ${apk.name}: ${leaked.sorted().joinToString()}"
                }
                val missing = required - entries
                check(missing.isEmpty()) {
                    "Required base runtime inputs missing from ${apk.name}: ${missing.sorted().joinToString()}"
                }
            }
        }

        val releaseBuildConfig = layout.buildDirectory
            .file("generated/source/buildConfig/release/dev/foldcode/ide/BuildConfig.java")
            .get()
            .asFile
        check(releaseBuildConfig.isFile && releaseBuildConfig.readText().contains(
            "public static final String EXTENSION_CATALOG_URL = \"\";",
        )) { "Release EXTENSION_CATALOG_URL must remain empty" }
    }
}

val coreUnitCheck by tasks.registering {
    group = "verification"
    description = "Runs the non-UI JVM tests and Android lint for both build variants."
    dependsOn(
        "testDebugUnitTest",
        "testReleaseUnitTest",
        "lintDebug",
        "lintRelease",
    )
}

val coreCheck by tasks.registering {
    group = "verification"
    description = "Runs all automated core checks and verifies both APK variants."
    dependsOn(coreUnitCheck, "assembleDebug", verifyReleaseApkContents)
}

tasks.register("publicRelease") {
    group = "build"
    description = "Builds the production-signed APK used for a public GitHub release."
    dependsOn(verifyReleaseApkContents)
    doLast {
        check(releaseSigningConfigured) {
            "Public release signing is not configured. Set FOLDCODE_RELEASE_STORE_FILE, " +
                "FOLDCODE_RELEASE_STORE_PASSWORD, FOLDCODE_RELEASE_KEY_ALIAS, and " +
                "FOLDCODE_RELEASE_KEY_PASSWORD outside the repository."
        }
    }
}

dependencies {
    implementation(platform("io.github.rosemoe:editor-bom:0.24.4"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Local JVM tests otherwise resolve Android's stub org.json classes, whose
    // methods throw "not mocked" before the Rust analyzer assertions can run.
    testImplementation("org.json:json:20251224")
}
