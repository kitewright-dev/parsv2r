package com.v2ray.ang.fmt

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * Builds and reads the JSON configs of the XDRIVE form.
 *
 * XDRIVE has no share-link format (3X-UI emits none on purpose: a link would print the
 * storage secrets into every QR code), so these profiles live as full JSON configs. The form
 * writes one fixed shape, and only a config that reads back into exactly the same JSON is
 * handed to the form again, so editing never drops what a panel or a person wrote by hand.
 */
object XDriveFmt {

    enum class Storage(val value: String) {
        GOOGLE_DRIVE("google-drive"),
        WEBDAV("webdav"),
        TEMPLATE("template");

        companion object {
            fun fromValue(value: String?): Storage = entries.firstOrNull { it.value == value } ?: GOOGLE_DRIVE
        }
    }

    data class Draft(
        val remarks: String = "",
        val id: String = "",
        val encryption: String = "",
        val storage: Storage = Storage.GOOGLE_DRIVE,
        /** Google Drive: the folder ID. WebDAV and template: the folder path. */
        val folder: String = "",
        val driveClientId: String = "",
        val driveClientSecret: String = "",
        val driveRefreshToken: String = "",
        val davUrl: String = "",
        val davUsername: String = "",
        val davPassword: String = "",
        /** Template only: the "template" object of xdriveSettings, as JSON. */
        val template: String = "",
        /** Template only: one secret per line, read by the template as {secret0}, {secret1}... */
        val templateSecrets: String = "",
    )

    enum class Problem {
        MISSING_ID,
        INVALID_ID,
        MISSING_ENCRYPTION,
        INVALID_ENCRYPTION,
        MISSING_FOLDER,
        INVALID_FOLDER,
        MISSING_DRIVE_SECRETS,
        INVALID_DAV_URL,
        MISSING_DAV_USERNAME,
        INVALID_TEMPLATE,
    }

    const val PROXY_TAG = "proxy"
    private const val XDRIVE = "xdrive"
    private const val DEFAULT_PORT = 443
    private const val ENCRYPTION_PREFIX = "mlkem768x25519plus."

    /**
     * Picks the last segment of every href in a PROPFIND answer, in whatever namespace prefix
     * the server writes (D:, d:, lp1: or none) and with a path or an absolute URL. The folder
     * itself ends in "/" and is left out. Written for Go's RE2, which the core compiles it with.
     */
    const val WEBDAV_NAMES_REGEX =
        "<(?:[A-Za-z][A-Za-z0-9._-]*:)?href>[^<]*/([^/<]+)</(?:[A-Za-z][A-Za-z0-9._-]*:)?href>"

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val FOLDER_PATH_REGEX = Regex("^[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$")
    private val DRIVE_ID_REGEX = Regex("^[A-Za-z0-9_-]+$")

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** The first problem of [draft] in form order, or null when it can be built. */
    fun validate(draft: Draft): Problem? {
        val id = draft.id.trim()
        when {
            id.isEmpty() -> return Problem.MISSING_ID
            // Xray also takes 1-30 bytes of any text and maps it to a UUID
            !UUID_REGEX.matches(id) && id.toByteArray().size > 30 -> return Problem.INVALID_ID
        }
        val encryption = draft.encryption.trim()
        when {
            encryption.isEmpty() -> return Problem.MISSING_ENCRYPTION
            !encryption.startsWith(ENCRYPTION_PREFIX) || encryption.split('.').size < 4 -> return Problem.INVALID_ENCRYPTION
        }
        val folder = draft.folder.trim()
        if (folder.isEmpty()) return Problem.MISSING_FOLDER
        when (draft.storage) {
            Storage.GOOGLE_DRIVE -> {
                if (!DRIVE_ID_REGEX.matches(folder)) return Problem.INVALID_FOLDER
                if (draft.driveClientId.isBlank() || draft.driveClientSecret.isBlank() || draft.driveRefreshToken.isBlank()) {
                    return Problem.MISSING_DRIVE_SECRETS
                }
            }

            Storage.WEBDAV -> {
                if (!FOLDER_PATH_REGEX.matches(folder)) return Problem.INVALID_FOLDER
                if (davBase(draft.davUrl) == null) return Problem.INVALID_DAV_URL
                if (draft.davUsername.isBlank()) return Problem.MISSING_DAV_USERNAME
            }

            Storage.TEMPLATE -> {
                if (!FOLDER_PATH_REGEX.matches(folder)) return Problem.INVALID_FOLDER
                if (!isUsableTemplate(draft.template)) return Problem.INVALID_TEMPLATE
            }
        }
        return null
    }

    /** The full config for [draft]; call [validate] first. */
    fun build(draft: Draft, socksPort: Int): String = gson.toJson(buildTree(draft, socksPort))

    /** Reads a config back into a draft only when the form would write it exactly as it is. */
    fun parseFormConfig(raw: String?): Draft? {
        raw ?: return null
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()?.asObject() ?: return null
        val draft = runCatching { readDraft(root) }.getOrNull() ?: return null
        val port = socksPortOf(root) ?: return null
        return draft.takeIf { validate(it) == null && buildTree(it, port) == root }
    }

    /** The SOCKS port the config listens on, used to rebuild it without moving the port. */
    fun socksPortOf(raw: String): Int? =
        runCatching { JsonParser.parseString(raw) }.getOrNull()?.asObject()?.let { socksPortOf(it) }

    private fun buildTree(draft: Draft, socksPort: Int): JsonObject {
        val xdrive = JsonObject().apply {
            addProperty("service", if (draft.storage == Storage.GOOGLE_DRIVE) "google-drive" else "template")
            addProperty("remoteFolder", draft.folder.trim())
            add("secrets", strings(secretsOf(draft)))
            if (draft.storage != Storage.GOOGLE_DRIVE) add("template", templateOf(draft))
        }
        val user = JsonObject().apply {
            addProperty("id", draft.id.trim())
            addProperty("encryption", draft.encryption.trim())
        }
        val server = JsonObject().apply {
            addProperty("address", displayHostOf(draft))
            addProperty("port", DEFAULT_PORT)
            add("users", JsonArray().apply { add(user) })
        }
        val proxy = JsonObject().apply {
            addProperty("tag", PROXY_TAG)
            addProperty("protocol", "vless")
            add("settings", JsonObject().apply { add("vnext", JsonArray().apply { add(server) }) })
            add("streamSettings", JsonObject().apply {
                addProperty("network", XDRIVE)
                add("xdriveSettings", xdrive)
            })
            // the core takes XDRIVE only with Mux.Cool carrying every connection
            add("mux", JsonObject().apply { addProperty("enabled", true) })
        }
        val socks = JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("listen", "127.0.0.1")
            addProperty("port", socksPort)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
            })
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", strings(listOf("http", "tls", "quic")))
            })
        }
        return JsonObject().apply {
            addProperty("remarks", draft.remarks.trim())
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("dns", JsonObject().apply { add("servers", strings(listOf("1.1.1.1", "8.8.8.8"))) })
            add("inbounds", JsonArray().apply { add(socks) })
            add("outbounds", JsonArray().apply {
                add(proxy)
                add(JsonObject().apply {
                    addProperty("tag", "direct")
                    addProperty("protocol", "freedom")
                })
                add(JsonObject().apply {
                    addProperty("tag", "block")
                    addProperty("protocol", "blackhole")
                })
            })
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "AsIs")
                add("rules", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "field")
                        addProperty("network", "tcp,udp")
                        addProperty("outboundTag", PROXY_TAG)
                    })
                })
            })
        }
    }

    private fun secretsOf(draft: Draft): List<String> = when (draft.storage) {
        Storage.GOOGLE_DRIVE -> listOf(draft.driveClientId, draft.driveClientSecret, draft.driveRefreshToken).map { it.trim() }
        Storage.WEBDAV -> listOf(draft.davUsername.trim(), draft.davPassword)
        Storage.TEMPLATE -> draft.templateSecrets.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun templateOf(draft: Draft): JsonObject = when (draft.storage) {
        Storage.WEBDAV -> webDavTemplate(davBase(draft.davUrl).orEmpty())
        else -> JsonParser.parseString(draft.template).asJsonObject
    }

    /**
     * A WebDAV storage as an XDRIVE template. "flatten" keeps every file directly in the
     * folder (the core writes "a/b" as "a~b"), so the folder needs no subfolders and no MKCOL.
     */
    internal fun webDavTemplate(base: String): JsonObject {
        val file = "$base/{folder}/{name}"
        fun op(method: String, url: String) = JsonObject().apply {
            addProperty("method", method)
            addProperty("url", url)
        }
        return JsonObject().apply {
            addProperty("flatten", true)
            add("auth", JsonObject().apply {
                addProperty("type", "basic")
                addProperty("username", "{secret0}")
                addProperty("password", "{secret1}")
            })
            add("put", op("PUT", file))
            add("get", op("GET", file))
            add("delete", op("DELETE", file))
            add("list", op("PROPFIND", "$base/{folder}/").apply {
                add("headers", JsonObject().apply { addProperty("Depth", "1") })
                addProperty("namesRegex", WEBDAV_NAMES_REGEX)
            })
        }
    }

    /** The WebDAV root as http(s)://host[:port][/path] without a trailing slash, or null. */
    internal fun davBase(url: String): String? {
        val trimmed = url.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        if (trimmed.contains('{') || trimmed.contains('}')) return null
        return trimmed
    }

    private fun isUsableTemplate(template: String): Boolean {
        val root = runCatching { JsonParser.parseString(template) }.getOrNull()?.asObject() ?: return false
        val hasUrls = listOf("put", "get", "delete", "list").all { root.obj(it)?.string("url")?.isNotBlank() == true }
        return hasUrls && root.obj("list")?.string("namesRegex")?.isNotBlank() == true
    }

    private fun displayHostOf(draft: Draft): String {
        val url = when (draft.storage) {
            Storage.GOOGLE_DRIVE -> return "www.googleapis.com"
            Storage.WEBDAV -> davBase(draft.davUrl)
            Storage.TEMPLATE -> runCatching { JsonParser.parseString(draft.template).asJsonObject.obj("put")?.string("url") }.getOrNull()
        }
        val host = url?.let { runCatching { URI(it.replace("{", "").replace("}", "")).host }.getOrNull() }
        return host?.takeIf { it.isNotBlank() } ?: XDRIVE
    }

    private fun readDraft(root: JsonObject): Draft? {
        val outbounds = root.array("outbounds") ?: return null
        val proxy = outbounds.mapNotNull { it.asObject() }.singleOrNull { it.string("tag") == PROXY_TAG } ?: return null
        val user = proxy.obj("settings")?.array("vnext")?.singleOrNull()?.asObject()
            ?.array("users")?.singleOrNull()?.asObject() ?: return null
        val xdrive = proxy.obj("streamSettings")?.obj("xdriveSettings") ?: return null
        val secrets = xdrive.array("secrets")?.map { it.asString } ?: return null
        val base = Draft(
            remarks = root.string("remarks").orEmpty(),
            id = user.string("id").orEmpty(),
            encryption = user.string("encryption").orEmpty(),
            folder = xdrive.string("remoteFolder").orEmpty(),
        )
        return when (xdrive.string("service")) {
            "google-drive" -> if (secrets.size != 3) null else base.copy(
                storage = Storage.GOOGLE_DRIVE,
                driveClientId = secrets[0],
                driveClientSecret = secrets[1],
                driveRefreshToken = secrets[2],
            )

            "template" -> {
                val template = xdrive.obj("template") ?: return null
                val davBase = webDavBaseOf(template)
                if (davBase != null && secrets.size == 2) {
                    base.copy(storage = Storage.WEBDAV, davUrl = davBase, davUsername = secrets[0], davPassword = secrets[1])
                } else {
                    base.copy(storage = Storage.TEMPLATE, template = gson.toJson(template), templateSecrets = secrets.joinToString("\n"))
                }
            }

            else -> null
        }
    }

    /** The WebDAV root when [template] is exactly what [webDavTemplate] writes for one. */
    private fun webDavBaseOf(template: JsonObject): String? {
        val put = template.obj("put")?.string("url") ?: return null
        val base = put.removeSuffix("/{folder}/{name}").takeIf { it != put } ?: return null
        return base.takeIf { davBase(it) == it && webDavTemplate(it) == template }
    }

    private fun socksPortOf(root: JsonObject): Int? = root.array("inbounds")
        ?.mapNotNull { it.asObject() }
        ?.firstOrNull { it.string("protocol") == "socks" }
        ?.get("port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }

    private fun JsonElement.asObject(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.asObject()

    private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
