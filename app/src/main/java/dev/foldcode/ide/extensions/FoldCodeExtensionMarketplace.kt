package dev.foldcode.ide

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

internal data class MarketplacePackage(
    val url: URL,
    val sha256: String,
    val sizeBytes: Long,
)

internal data class MarketplacePayload(
    val name: String,
    val url: URL,
    val sha256: String,
    val sizeBytes: Long,
)

internal data class MarketplaceExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val publisher: String,
    val dependencies: Set<String>,
    val provides: Set<String>,
    val corePackage: MarketplacePackage,
    val payloads: Map<String, MarketplacePayload>,
)

/** HTTPS catalog v2 and independent component transport. */
internal class FoldCodeExtensionMarketplace(
    private val catalogUrl: String = BuildConfig.EXTENSION_CATALOG_URL,
) {
    val configured: Boolean get() = catalogUrl.isNotBlank()

    fun extensions(): List<MarketplaceExtension> {
        require(configured) {
            "Extension server is not configured. Set foldcodeExtensionCatalogUrl when building FoldCode."
        }
        val catalog = URL(catalogUrl)
        requireSecure(catalog)
        val connection = open(catalog)
        val json = try {
            val bytes = connection.inputStream.use { it.readUpTo(MAX_CATALOG_BYTES + 1) }
            require(bytes.size <= MAX_CATALOG_BYTES) { "Extension catalog is too large" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
        require(json.getInt("schemaVersion") == 2) { "Unsupported extension catalog version" }
        val array = json.getJSONArray("extensions")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val core = item.getJSONObject("corePackage")
                val payloadJson = item.getJSONObject("payloads")
                val payloads = payloadJson.keys().asSequence().associateWith { name ->
                    val payload = payloadJson.getJSONObject(name)
                    MarketplacePayload(
                        name = name,
                        url = resolvedUrl(catalog, payload.getString("url")),
                        sha256 = checkedHash(payload.getString("sha256")),
                        sizeBytes = checkedSize(payload.getLong("sizeBytes")),
                    )
                }
                val dependencies = item.optJSONArray("dependencies")?.let { values ->
                    buildSet { for (dependency in 0 until values.length()) add(values.getString(dependency)) }
                }.orEmpty()
                val provides = item.optJSONArray("provides")?.let { values ->
                    buildSet { for (capability in 0 until values.length()) add(values.getString(capability)) }
                }.orEmpty()
                add(
                    MarketplaceExtension(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        version = item.getString("version"),
                        description = item.optString("description"),
                        publisher = item.optString("publisher", "FoldCode"),
                        dependencies = dependencies,
                        provides = provides,
                        corePackage = MarketplacePackage(
                            url = resolvedUrl(catalog, core.getString("url")),
                            sha256 = checkedHash(core.getString("sha256")),
                            sizeBytes = checkedSize(core.getLong("sizeBytes")),
                        ),
                        payloads = payloads,
                    ),
                )
            }
        }
    }

    fun extension(id: String): MarketplaceExtension =
        extensions().firstOrNull { it.id == id } ?: error("Extension $id is not present in the server catalog")

    fun picoExtension(): MarketplaceExtension = extension(PICO_EXTENSION_ID)

    fun cppExtension(): MarketplaceExtension = extension(CPP_EXTENSION_ID)

    fun pythonExtension(): MarketplaceExtension = extension(PYTHON_EXTENSION_ID)

    /** Legacy detail tabs may survive an app upgrade; Git is no longer catalog-delivered. */
    fun gitExtension(): MarketplaceExtension = extension(GIT_EXTENSION_ID)

    fun gnuArmExtension(): MarketplaceExtension = extension(GNU_ARM_EXTENSION_ID)

    fun rustExtension(): MarketplaceExtension = extension(RUST_EXTENSION_ID)

    fun gnuLanguagesExtension(): MarketplaceExtension = extension(GNU_LANGUAGES_EXTENSION_ID)

    fun webExtension(): MarketplaceExtension = extension(WEB_EXTENSION_ID)

    fun downloadAndInstall(
        extension: MarketplaceExtension,
        manager: FoldCodeExtensionManager,
        cacheDirectory: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): FoldCodeExtensionInfo {
        require(extension.id in setOf(PICO_EXTENSION_ID, CPP_EXTENSION_ID, PYTHON_EXTENSION_ID, GNU_ARM_EXTENSION_ID, RUST_EXTENSION_ID, GNU_LANGUAGES_EXTENSION_ID, WEB_EXTENSION_ID)) { "Unsupported marketplace extension" }
        if (extension.id == PICO_EXTENSION_ID) require(extension.version == PicoSdkRelease.extensionVersion) {
            "Server has Pico ${extension.version}; this FoldCode build requires ${PicoSdkRelease.extensionVersion}"
        }
        val temporary = download(extension.corePackage, cacheDirectory, ".fcex", onProgress)
        return try {
            temporary.inputStream().buffered().use(manager::install)
        } finally {
            temporary.delete()
        }
    }

    fun downloadPayloads(
        extension: MarketplaceExtension,
        names: Set<String>,
        manager: FoldCodeExtensionManager,
        cacheDirectory: File,
        onProgress: (name: String, downloaded: Long, total: Long) -> Unit,
    ) {
        names.forEach { name ->
            val payload = extension.payloads[name] ?: error("Server catalog has no payload $name")
            val temporary = download(
                MarketplacePackage(payload.url, payload.sha256, payload.sizeBytes),
                cacheDirectory,
                ".zip",
            ) { downloaded, total -> onProgress(name, downloaded, total) }
            try {
                temporary.inputStream().buffered().use { manager.receivePayload(extension.id, name, it) }
            } finally {
                // Download archives are transport artifacts, never permanent storage.
                temporary.delete()
            }
        }
    }

    private fun download(
        remote: MarketplacePackage,
        cacheDirectory: File,
        suffix: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val temporary = File(cacheDirectory, "extension-${UUID.randomUUID()}$suffix")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val connection = open(remote.url)
            try {
                val responseBytes = connection.contentLengthLong
                require(responseBytes <= 0 || responseBytes <= MAX_PACKAGE_BYTES) { "Extension package is too large" }
                var downloaded = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                connection.inputStream.buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            require(downloaded <= MAX_PACKAGE_BYTES) { "Extension package is too large" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(downloaded, remote.sizeBytes)
                        }
                    }
                }
                require(downloaded == remote.sizeBytes) {
                    "Extension download is incomplete ($downloaded of ${remote.sizeBytes} bytes)"
                }
            } finally {
                connection.disconnect()
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == remote.sha256) { "Downloaded extension checksum failed" }
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun resolvedUrl(catalog: URL, value: String): URL =
        URL(URI(catalog.toString()).resolve(value).toString()).also(::requireSecure)

    private fun checkedHash(value: String): String = value.lowercase().also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid extension checksum in catalog" }
    }

    private fun checkedSize(value: Long): Long = value.also {
        require(it in 1..MAX_PACKAGE_BYTES) { "Invalid extension package size" }
    }

    private fun open(url: URL): HttpURLConnection {
        requireSecure(url)
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, application/octet-stream")
            setRequestProperty("User-Agent", "FoldCode/${BuildConfig.VERSION_NAME}")
            connect()
            require(responseCode in 200..299) { "Extension server returned HTTP $responseCode" }
            requireSecure(this.url)
        }
    }

    private fun requireSecure(url: URL) {
        // Debug builds may connect to a contributor's LAN registry. Release builds
        // always require HTTPS, even when pointed at a private address.
        val localDebug = BuildConfig.DEBUG && url.protocol.equals("http", ignoreCase = true)
        require(url.protocol.equals("https", ignoreCase = true) || localDebug) {
            "Extension downloads require HTTPS outside local debug builds"
        }
    }

    private companion object {
        const val PICO_EXTENSION_ID = "dev.foldcode.pico"
        const val CPP_EXTENSION_ID = "dev.foldcode.cpp"
        const val PYTHON_EXTENSION_ID = "dev.foldcode.python"
        const val GIT_EXTENSION_ID = "dev.foldcode.git"
        const val GNU_ARM_EXTENSION_ID = "dev.foldcode.gnu-arm"
        const val RUST_EXTENSION_ID = "dev.foldcode.rust"
        const val GNU_LANGUAGES_EXTENSION_ID = "dev.foldcode.gnu-languages"
        const val WEB_EXTENSION_ID = "dev.foldcode.web"
        const val MAX_CATALOG_BYTES = 512 * 1024
        const val MAX_PACKAGE_BYTES = 600L * 1024 * 1024
    }
}
