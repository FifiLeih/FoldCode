package dev.foldcode.ide

/** Stable key used to restart runtime consumers after an extension changes. */
internal fun extensionRuntimeKey(vararg extensions: FoldCodeExtensionInfo?): String =
    extensions.joinToString("|") { extension ->
        extension?.let { "${it.id}@${it.version}" } ?: "-"
    }
