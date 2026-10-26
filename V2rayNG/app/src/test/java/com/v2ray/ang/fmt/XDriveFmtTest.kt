package com.v2ray.ang.fmt

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.core.XDriveRepair
import com.v2ray.ang.fmt.XDriveFmt.Draft
import com.v2ray.ang.fmt.XDriveFmt.Problem
import com.v2ray.ang.fmt.XDriveFmt.Storage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class XDriveFmtTest {

    private val id = "5f0c3a2e-8d21-4b7e-9a51-2c6f0e9b7d10"

    // a throwaway X25519 pair from `xray vlessenc`, also used by the end-to-end check in CI
    private val encryption = "mlkem768x25519plus.native.0rtt.uZUcpIROvDzqMdk55aR3-iPUqeCF22lTDTXd90KRVTs"

    private val drive = Draft(
        remarks = "drive",
        id = id,
        encryption = encryption,
        storage = Storage.GOOGLE_DRIVE,
        folder = "1AbC-dEf_9",
        driveClientId = "client.apps.googleusercontent.com",
        driveClientSecret = "secret",
        driveRefreshToken = "1//token",
    )

    private val dav = Draft(
        remarks = "dav",
        id = id,
        encryption = encryption,
        storage = Storage.WEBDAV,
        folder = "tunnel",
        davUrl = "http://127.0.0.1:18090/dav",
        davUsername = "jmc",
        davPassword = "kite-pass",
    )

    private val template = Draft(
        remarks = "template",
        id = id,
        encryption = encryption,
        storage = Storage.TEMPLATE,
        folder = "box",
        template = GsonBuilder().create().toJson(XDriveFmt.webDavTemplate("https://other.example/x").apply {
            addProperty("concurrency", 4)
        }),
        templateSecrets = "u\np",
    )

    private fun tree(raw: String) = JsonParser.parseString(raw).asJsonObject

    private fun JsonObject.proxy() = getAsJsonArray("outbounds")
        .map { it.asJsonObject }.single { it.get("tag").asString == XDriveFmt.PROXY_TAG }

    private fun JsonObject.xdrive() = proxy().getAsJsonObject("streamSettings").getAsJsonObject("xdriveSettings")

    @Test
    fun acceptsEveryCompleteDraft() {
        for (draft in listOf(drive, dav, template)) {
            assertNull(XDriveFmt.validate(draft), draft.storage.name)
        }
        // a short text ID is what Xray maps to a UUID itself
        assertNull(XDriveFmt.validate(drive.copy(id = "my-user")))
    }

    @Test
    fun reportsTheFirstProblemOfEachKind() {
        val cases = listOf(
            drive.copy(id = " ") to Problem.MISSING_ID,
            drive.copy(id = "x".repeat(31)) to Problem.INVALID_ID,
            drive.copy(encryption = "") to Problem.MISSING_ENCRYPTION,
            drive.copy(encryption = "none") to Problem.INVALID_ENCRYPTION,
            drive.copy(encryption = "mlkem768x25519plus.native") to Problem.INVALID_ENCRYPTION,
            drive.copy(folder = "") to Problem.MISSING_FOLDER,
            drive.copy(folder = "a/b") to Problem.INVALID_FOLDER,
            drive.copy(driveRefreshToken = "") to Problem.MISSING_DRIVE_SECRETS,
            dav.copy(folder = "a b") to Problem.INVALID_FOLDER,
            dav.copy(davUrl = "ftp://host/x") to Problem.INVALID_DAV_URL,
            dav.copy(davUrl = "https://") to Problem.INVALID_DAV_URL,
            dav.copy(davUrl = "https://host/x?y=1") to Problem.INVALID_DAV_URL,
            dav.copy(davUrl = "https://host/{folder}") to Problem.INVALID_DAV_URL,
            dav.copy(davUsername = "") to Problem.MISSING_DAV_USERNAME,
            template.copy(template = "[]") to Problem.INVALID_TEMPLATE,
            template.copy(template = "{\"put\":{\"url\":\"u\"}}") to Problem.INVALID_TEMPLATE,
            template.copy(template = template.template.replace("namesRegex", "other")) to Problem.INVALID_TEMPLATE,
        )
        for ((draft, problem) in cases) {
            assertEquals(problem, XDriveFmt.validate(draft), draft.toString())
        }
    }

    @Test
    fun buildsGoogleDriveWithTheSecretsInTheOrderTheCoreReads() {
        val root = tree(XDriveFmt.build(drive, 10808))
        val xdrive = root.xdrive()
        assertEquals("google-drive", xdrive.get("service").asString)
        assertEquals("1AbC-dEf_9", xdrive.get("remoteFolder").asString)
        assertEquals(listOf("client.apps.googleusercontent.com", "secret", "1//token"), xdrive.getAsJsonArray("secrets").map { it.asString })
        assertNull(xdrive.get("template"))
        assertEquals("www.googleapis.com", root.proxy().getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.get("address").asString)
        assertEquals(10808, root.getAsJsonArray("inbounds")[0].asJsonObject.get("port").asInt)
    }

    @Test
    fun buildsWebDavAsABasicAuthTemplateOnTheGivenRoot() {
        val xdrive = tree(XDriveFmt.build(dav.copy(davUrl = "http://127.0.0.1:18090/dav/"), 1080)).xdrive()
        assertEquals("template", xdrive.get("service").asString)
        assertEquals(listOf("jmc", "kite-pass"), xdrive.getAsJsonArray("secrets").map { it.asString })
        val tmpl = xdrive.getAsJsonObject("template")
        assertEquals("http://127.0.0.1:18090/dav/{folder}/{name}", tmpl.getAsJsonObject("put").get("url").asString)
        assertEquals("PROPFIND", tmpl.getAsJsonObject("list").get("method").asString)
        assertEquals("http://127.0.0.1:18090/dav/{folder}/", tmpl.getAsJsonObject("list").get("url").asString)
        assertEquals("{secret0}", tmpl.getAsJsonObject("auth").get("username").asString)
    }

    @Test
    fun writesConfigsTheRepairHasNothingToChangeIn() {
        for (draft in listOf(drive, dav, template)) {
            val root = tree(XDriveFmt.build(draft, 10808))
            val report = XDriveRepair.repair(root)
            assertFalse(report.changed, draft.storage.name)
            assertEquals(0, report.missingEncryption)
            assertEquals(1, report.outbounds)
        }
    }

    @Test
    fun readsItsOwnConfigsBackForEveryStorage() {
        for (draft in listOf(drive, dav, template)) {
            val read = XDriveFmt.parseFormConfig(XDriveFmt.build(draft, 2080))!!
            // the template comes back pretty-printed, so it is compared as JSON
            assertEquals(draft.copy(template = ""), read.copy(template = ""), draft.storage.name)
            assertEquals(tree(draft.template.ifEmpty { "{}" }), tree(read.template.ifEmpty { "{}" }), draft.storage.name)
        }
        assertEquals(2080, XDriveFmt.socksPortOf(XDriveFmt.build(dav, 2080)))
    }

    @Test
    fun keepsAnyEditedConfigAwayFromTheForm() {
        val built = tree(XDriveFmt.build(dav, 10808))
        built.proxy().getAsJsonObject("streamSettings").add("sockopt", JsonObject().apply { addProperty("mark", 1) })
        assertNull(XDriveFmt.parseFormConfig(built.toString()))

        val renamed = tree(XDriveFmt.build(drive, 10808)).apply { getAsJsonObject("routing").addProperty("domainStrategy", "IPIfNonMatch") }
        assertNull(XDriveFmt.parseFormConfig(renamed.toString()))

        assertNull(XDriveFmt.parseFormConfig("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}"""))
        assertNull(XDriveFmt.parseFormConfig("not json"))
        assertNull(XDriveFmt.parseFormConfig(null))
    }

    @Test
    fun readsAWebDavTemplateOfAnotherShapeAsATemplate() {
        val read = XDriveFmt.parseFormConfig(XDriveFmt.build(template, 10808))
        assertEquals(Storage.TEMPLATE, read?.storage)
        assertEquals("u\np", read?.templateSecrets)
    }

    @Test
    fun namesRegexFindsTheFilesOfEveryCommonWebDavServer() {
        val regex = Regex(XDriveFmt.WEBDAV_NAMES_REGEX)
        val answers = mapOf(
            // WsgiDAV, and the small server of the CI check, which writes the same shape
            "wsgidav" to """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/tunnel/</D:href></D:response><D:response><D:href>/dav/tunnel/streams~ab~c2s~000000001.seg</D:href></D:response><D:response><D:href>/dav/tunnel/sessions~171-ab</D:href></D:response></D:multistatus>""",
            // Nextcloud and ownCloud write a lowercase prefix
            "nextcloud" to """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav/files/me/tunnel/</d:href></d:response><d:response><d:href>/remote.php/dav/files/me/tunnel/streams~ab~c2s~000000001.seg</d:href></d:response><d:response><d:href>/remote.php/dav/files/me/tunnel/sessions~171-ab</d:href></d:response></d:multistatus>""",
            // Apache mod_dav may answer with absolute URLs
            "apache" to """<D:multistatus xmlns:D="DAV:"><D:response xmlns:lp1="DAV:"><D:href>https://dav.example/dav/tunnel/</D:href></D:response><D:response><D:href>https://dav.example/dav/tunnel/streams~ab~c2s~000000001.seg</D:href></D:response><D:response><D:href>https://dav.example/dav/tunnel/sessions~171-ab</D:href></D:response></D:multistatus>""",
            // and some servers use the default namespace with no prefix at all
            "plain" to """<multistatus xmlns="DAV:"><response><href>/tunnel/</href></response><response><href>/tunnel/streams~ab~c2s~000000001.seg</href></response><response><href>/tunnel/sessions~171-ab</href></response></multistatus>""",
        )
        for ((server, body) in answers) {
            assertEquals(
                listOf("streams~ab~c2s~000000001.seg", "sessions~171-ab"),
                regex.findAll(body).map { it.groupValues[1] }.toList(),
                server
            )
        }
    }

    @Test
    fun normalisesTheWebDavRoot() {
        assertEquals("https://dav.example/a", XDriveFmt.davBase(" https://dav.example/a/ "))
        assertEquals("http://127.0.0.1:18090", XDriveFmt.davBase("http://127.0.0.1:18090"))
        assertNull(XDriveFmt.davBase("dav.example/a"))
        assertNull(XDriveFmt.davBase("https://user:pw@dav.example/a"))
    }

    /**
     * Writes what the app hands the core, so CI can run it through the real core and a real
     * WebDAV server (see .github/workflows/build.yml). The panel config is checked twice: as the
     * panel ships it, which the core must refuse, and as the repair leaves it, which must work.
     */
    @Test
    fun writesTheConfigsTheCoreCheckRuns() {
        val dir = File("build/xdrive-e2e").apply { mkdirs() }
        val gson = GsonBuilder().setPrettyPrinting().create()
        File(dir, "form_webdav.json").writeText(XDriveFmt.build(dav, 10808))

        val panel = tree(XDriveFmt.build(dav, 10808)).apply {
            val proxy = proxy()
            proxy.remove("mux")
            proxy.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
                .getAsJsonArray("users")[0].asJsonObject.addProperty("flow", "")
        }
        File(dir, "panel_nomux.raw.json").writeText(gson.toJson(panel))
        val report = XDriveRepair.repair(panel)
        assertEquals(1, report.muxFixed)
        File(dir, "panel_nomux.repaired.json").writeText(gson.toJson(panel))
    }
}
