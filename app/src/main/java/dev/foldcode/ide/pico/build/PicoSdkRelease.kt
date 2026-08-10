package dev.foldcode.ide

/**
 * The single Raspberry Pi Pico SDK/toolchain release shipped by FoldCode.
 *
 * Updating Pico support starts here, then regenerates every platform asset with
 * tools/update-pico-sdk-bundles.sh. Board-specific bundles contain configured
 * component archives, but all of them are produced from this same release.
 */
internal object PicoSdkRelease {
    const val sdkVersion = "2.3.0"
    const val toolchainRelease = "15_2_Rel1"
    const val gccVersion = "15.2.1"
    const val riscVGccVersion = "15.2.0"

    // Increment only when package contents/layout change without a version change.
    const val bundleRevision = 18

    val displayName get() = "Official Pico SDK $sdkVersion · GNU Arm $gccVersion"
    val installId get() = "pico-sdk-$sdkVersion-gnu-$gccVersion-v$bundleRevision"
    val extensionVersion get() = "$sdkVersion+$bundleRevision"
}
