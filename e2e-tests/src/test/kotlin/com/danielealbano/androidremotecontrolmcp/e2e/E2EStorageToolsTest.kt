package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * E2E Tier 1 core storage suite: all 8 file tools against the real MediaProvider,
 * covering the regression classes of #154 (path filtering), #155 (multi-collection
 * DCIM/Pictures), #156 (recordings), permission/toggle states, downloads via the
 * host-side fixture server, and pagination.
 *
 * Tests are ordered (@Order = plan table row) because rows 12/13/16/17/18 form a
 * mutable-file chain on e2e-rt.txt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EStorageToolsTest {

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        private const val DOWNLOADS = "builtin:downloads"
        private const val STALL_START_TIMEOUT_S = 30L
        private const val STALL_STOP_TIMEOUT_S = 10L
        private const val STALL_PROMPT_STOP_MS = 5_000L
        private const val POLL_MS = 250L
        private const val CANCELLED_ID = 9001
        private const val HTTP_ACCEPTED = 202
        private const val PICTURES = "builtin:pictures"
        private const val MOVIES = "builtin:movies"
        private const val MUSIC = "builtin:music"
        private const val DCIM = "builtin:dcim"
        private const val RECORDINGS = "builtin:recordings"
        private const val BULK_COUNT = 210
    }

    private val mcpClient get() = SharedAndroidContainer.mcpClient
    private val fixtureServer = FixtureHttpServer()
    private lateinit var fixtureBaseUrl: String

    @BeforeAll
    fun setUpStorage() {
        // Force shared-container initialization: the container boots lazily on first
        // access, and every StorageE2E call below requires a booted container (adb).
        SharedAndroidContainer.ensureAccessibilityService()

        StorageE2E.grantAllMediaPermissions()
        for (location in listOf(DOWNLOADS, PICTURES, DCIM, RECORDINGS, MUSIC)) {
            StorageE2E.configureStorageLocation(location, allowWrite = true, allowDelete = true)
        }
        StorageE2E.configureDownloadSettings(10, allowHttp = true)

        fixtureServer.start()
        fixtureBaseUrl = fixtureServer.containerReachableBaseUrl()

        StorageE2E.seedFile("DCIM/Camera/photo1.jpg", "jpeg-fixture-1")
        StorageE2E.seedFile("DCIM/Camera/video1.mp4", "mp4-fixture-1")
        StorageE2E.seedFile("DCIM/Screenshots/shot1.png", "png-fixture-1")
        StorageE2E.seedFile("Pictures/Vacation/pic1.jpg", "jpeg-fixture-2")
        StorageE2E.seedFile("Pictures/clip.mp4", "mp4-fixture-2")
        StorageE2E.seedFile("Pictures/readable.jpg", "line one\nline two")
        StorageE2E.seedFile("Pictures/depth/notes.jpg", "notes")
        StorageE2E.seedBulk("Pictures/bulk", BULK_COUNT)
        StorageE2E.seedFile("Music/Album/song.mp3", "mp3-fixture")
        StorageE2E.seedFile("Recordings/memo.m4a", "m4a-fixture")
        StorageE2E.seedFile("Download/seeded.txt", "invisible")
        StorageE2E.scanVolume()
    }

    @AfterAll
    fun tearDownStorage() {
        fixtureServer.stop()
        StorageE2E.removeFixtureTree("DCIM/Camera")
        StorageE2E.removeFixtureTree("DCIM/Screenshots")
        StorageE2E.removeFixtureTree("Pictures/Vacation")
        StorageE2E.removeFixtureTree("Pictures/depth")
        StorageE2E.removeFixtureTree("Pictures/bulk")
        StorageE2E.removeFromDisk("Pictures/clip.mp4")
        StorageE2E.removeFromDisk("Pictures/readable.jpg")
        StorageE2E.removeFromDisk("Pictures/readable (1).jpg")
        StorageE2E.removeFromDisk("Pictures/late.jpg")
        StorageE2E.removeFixtureTree("Music/Album")
        StorageE2E.removeFromDisk("Recordings/memo.m4a")
        StorageE2E.removeFromDisk("Download/seeded.txt")
        // Owned leftovers from write/download tests (best-effort; some are deleted by tests)
        StorageE2E.removeFromDisk("Download/e2e-dl.txt")
        StorageE2E.removeFromDisk("Download/e2e-truncated.txt")
        StorageE2E.removeFromDisk("DCIM/e2e-shot.jpg")
        StorageE2E.scanVolume()
        for (location in listOf(DOWNLOADS, PICTURES, DCIM, RECORDINGS, MUSIC)) {
            StorageE2E.configureStorageLocation(location, allowWrite = false, allowDelete = false)
        }
        StorageE2E.configureDownloadSettings(60, allowHttp = false)
    }

    // ─── shared helpers ─────────────────────────────────────────────────────

    private fun toolText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        (result.content.first() as TextContent).text

    private fun callListFiles(
        locationId: String,
        path: String = "",
        offset: Int = 0,
        limit: Int = 200,
    ): JsonObject = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_files",
            mapOf("location_id" to locationId, "path" to path, "offset" to offset, "limit" to limit),
        )
        assertNotEquals(true, result.isError, "list_files failed: ${toolText(result)}")
        Json.parseToJsonElement(stripUntrustedWarning(toolText(result))).jsonObject
    }

    private fun fileNames(listing: JsonObject): List<String> =
        listing["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun dirNames(listing: JsonObject): List<String> =
        listing["files"]!!.jsonArray
            .filter { it.jsonObject["is_directory"]!!.jsonPrimitive.boolean }
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun plainFileNames(listing: JsonObject): List<String> =
        listing["files"]!!.jsonArray
            .filter { !it.jsonObject["is_directory"]!!.jsonPrimitive.boolean }
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun totalCount(listing: JsonObject): Int = listing["total_count"]!!.jsonPrimitive.int

    private fun hasMore(listing: JsonObject): Boolean = listing["has_more"]!!.jsonPrimitive.boolean

    /**
     * Reads a file via read_file and strips the "N| " line-number prefixes the tool
     * adds (output format: "1| line content\n2| ..." + optional "--- More lines ---" trailer).
     */
    private fun readFileContent(locationId: String, path: String): String = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}read_file",
            mapOf("location_id" to locationId, "path" to path),
        )
        assertNotEquals(true, result.isError, "read_file failed: ${toolText(result)}")
        stripUntrustedWarning(toolText(result))
            .lines()
            .filterNot { it.startsWith("--- More lines available") }
            .joinToString("\n") { it.replace(Regex("^\\d+\\| "), "") }
    }

    private fun writeFile(locationId: String, path: String, content: String) = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}write_file",
            mapOf("location_id" to locationId, "path" to path, "content" to content),
        )
        assertNotEquals(true, result.isError, "write_file failed: ${toolText(result)}")
    }

    // ─── tests ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `list_storage_locations returns six builtins with access levels`() = runBlocking {
        val result = mcpClient.callTool("${TOOL_PREFIX}list_storage_locations", emptyMap())
        assertNotEquals(true, result.isError)
        val raw = toolText(result)
        assertNotEquals(raw, stripUntrustedWarning(raw), "response must begin with the untrusted warning")
        val locations = Json.parseToJsonElement(stripUntrustedWarning(raw)).jsonArray.map { it.jsonObject }
        val ids = locations.map { it["id"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(DOWNLOADS, PICTURES, MOVIES, MUSIC, DCIM, RECORDINGS),
            ids.filter { it.startsWith("builtin:") },
        )
        val byId = locations.associateBy { it["id"]!!.jsonPrimitive.content }
        for (id in listOf(PICTURES, MOVIES, MUSIC, DCIM, RECORDINGS)) {
            assertEquals("full", byId[id]!!["access_level"]!!.jsonPrimitive.content, id)
            assertTrue(byId[id]!!["name"]!!.jsonPrimitive.content.endsWith("- All files"), id)
        }
        assertEquals("owned_only", byId[DOWNLOADS]!!["access_level"]!!.jsonPrimitive.content)
        assertTrue(byId[DOWNLOADS]!!["name"]!!.jsonPrimitive.content.endsWith("- Only owned files"))
    }

    @Test
    @Order(2)
    fun `dcim root synthesizes camera and screenshots directories`() {
        val dirs = dirNames(callListFiles(DCIM))
        assertTrue(dirs.contains("Camera"), "dirs=$dirs")
        assertTrue(dirs.contains("Screenshots"), "dirs=$dirs")
    }

    @Test
    @Order(3)
    fun `dcim camera merges images and videos`() {
        val names = fileNames(callListFiles(DCIM, "Camera"))
        assertTrue(names.contains("photo1.jpg"), "names=$names")
        assertTrue(names.contains("video1.mp4"), "names=$names")
    }

    @Test
    @Order(4)
    fun `pictures root includes video stored under pictures`() {
        val names = plainFileNames(callListFiles(PICTURES))
        assertTrue(names.contains("clip.mp4"), "names=$names")
    }

    @Test
    @Order(5)
    fun `single segment path constrains listing`() {
        val names = fileNames(callListFiles(PICTURES, "Vacation"))
        assertEquals(listOf("pic1.jpg"), names)
    }

    @Test
    @Order(6)
    fun `nested path constrains listing`() {
        val depthNames = fileNames(callListFiles(PICTURES, "depth"))
        assertEquals(listOf("notes.jpg"), depthNames)
        val root = callListFiles(PICTURES)
        assertTrue(dirNames(root).contains("depth"))
        assertFalse(plainFileNames(root).contains("notes.jpg"))
    }

    @Test
    @Order(7)
    fun `unknown path returns empty listing`() {
        assertEquals(0, totalCount(callListFiles(DCIM, "DoesNotExist")))
    }

    @Test
    @Order(8)
    fun `recordings lists seeded audio`() {
        assertTrue(fileNames(callListFiles(RECORDINGS)).contains("memo.m4a"))
    }

    @Test
    @Order(9)
    fun `music lists seeded audio`() {
        assertEquals(listOf("song.mp3"), fileNames(callListFiles(MUSIC, "Album")))
    }

    @Test
    @Order(10)
    fun `read_file reads non-owned file in all-files mode`() {
        assertEquals("line one\nline two", readFileContent(PICTURES, "readable.jpg"))
    }

    @Test
    @Order(11)
    fun `downloads hides non-owned seeded files`() {
        assertFalse(
            fileNames(callListFiles(DOWNLOADS)).contains("seeded.txt"),
            "non-owned seeded file must be invisible in the owned-only downloads location",
        )
    }

    @Test
    @Order(12)
    fun `write_file then read_file round trip`() {
        writeFile(DOWNLOADS, "e2e-rt.txt", "round-trip-content")
        assertEquals("round-trip-content", readFileContent(DOWNLOADS, "e2e-rt.txt"))
    }

    @Test
    @Order(13)
    fun `written file appears in listing without manual scan`() {
        assertTrue(fileNames(callListFiles(DOWNLOADS)).contains("e2e-rt.txt"))
    }

    @Test
    @Order(14)
    fun `write_file routes image to dcim and lists it`() {
        writeFile(DCIM, "e2e-shot.jpg", "shot")
        assertTrue(plainFileNames(callListFiles(DCIM)).contains("e2e-shot.jpg"))
    }

    @Test
    @Order(15)
    fun `write_file rejects wrong mime with accepted types`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}write_file",
            mapOf("location_id" to PICTURES, "path" to "x.txt", "content" to "text"),
        )
        assertEquals(true, result.isError)
        assertTrue(toolText(result).contains("images, videos"), toolText(result))
    }

    @Test
    @Order(16)
    fun `append_file appends to owned file`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}append_file",
            mapOf("location_id" to DOWNLOADS, "path" to "e2e-rt.txt", "content" to "\nappended-line"),
        )
        assertNotEquals(true, result.isError, toolText(result))
        assertTrue(readFileContent(DOWNLOADS, "e2e-rt.txt").contains("appended-line"))
    }

    @Test
    @Order(17)
    fun `file_replace replaces text in owned file`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}file_replace",
            mapOf(
                "location_id" to DOWNLOADS,
                "path" to "e2e-rt.txt",
                "old_string" to "round-trip-content",
                "new_string" to "replaced-content",
            ),
        )
        assertNotEquals(true, result.isError, toolText(result))
        assertTrue(readFileContent(DOWNLOADS, "e2e-rt.txt").contains("replaced-content"))
    }

    @Test
    @Order(18)
    fun `delete_file removes owned file and repeat errors not found`() = runBlocking {
        val first = mcpClient.callTool(
            "${TOOL_PREFIX}delete_file",
            mapOf("location_id" to DOWNLOADS, "path" to "e2e-rt.txt"),
        )
        assertNotEquals(true, first.isError, toolText(first))
        assertFalse(fileNames(callListFiles(DOWNLOADS)).contains("e2e-rt.txt"))
        val second = mcpClient.callTool(
            "${TOOL_PREFIX}delete_file",
            mapOf("location_id" to DOWNLOADS, "path" to "e2e-rt.txt"),
        )
        assertEquals(true, second.isError)
        assertTrue(toolText(second).contains("not found", ignoreCase = true), toolText(second))
    }

    @Test
    @Order(19)
    fun `write denied when allow write disabled`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}write_file",
            mapOf("location_id" to MOVIES, "path" to "denied.mp4", "content" to "x"),
        )
        assertEquals(true, result.isError)
        assertTrue(toolText(result).contains("not allowed", ignoreCase = true), toolText(result))
    }

    @Test
    @Order(20)
    fun `delete denied when allow delete disabled`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}delete_file",
            mapOf("location_id" to MOVIES, "path" to "whatever.mp4"),
        )
        assertEquals(true, result.isError)
        assertTrue(toolText(result).contains("not allowed", ignoreCase = true), toolText(result))
    }

    @Test
    @Order(21)
    fun `delete of non-owned file reports not found`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}delete_file",
            mapOf("location_id" to PICTURES, "path" to "clip.mp4"),
        )
        assertEquals(true, result.isError)
        assertTrue(toolText(result).contains("not found", ignoreCase = true), toolText(result))
        // Deletes resolve OWNED files only: the tool can never touch non-owned files.
        assertTrue(plainFileNames(callListFiles(PICTURES)).contains("clip.mp4"))
    }

    @Test
    @Order(22)
    fun `write_file to existing non-owned name`() = runBlocking<Unit> {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}write_file",
            mapOf("location_id" to PICTURES, "path" to "readable.jpg", "content" to "overwrite-attempt"),
        )
        // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
        // the write succeeds and MediaStore auto-renames the new row to "readable (1).jpg";
        // the non-owned original row and its content are untouched.
        assertNotEquals(true, result.isError, toolText(result))
        val names = plainFileNames(callListFiles(PICTURES))
        assertTrue(names.contains("readable.jpg"), "names=$names")
        assertTrue(names.contains("readable (1).jpg"), "names=$names")
        assertEquals("line one\nline two", readFileContent(PICTURES, "readable.jpg"))
    }

    @Test
    @Order(23)
    fun `download_from_url downloads fixture file`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}download_from_url",
            mapOf("location_id" to DOWNLOADS, "path" to "e2e-dl.txt", "url" to "$fixtureBaseUrl/fixture.txt"),
        )
        assertNotEquals(true, result.isError, toolText(result))
        assertEquals(FixtureHttpServer.FIXTURE_CONTENT, readFileContent(DOWNLOADS, "e2e-dl.txt"))
    }

    @Test
    @Order(24)
    fun `download_from_url 404 returns error and leaves no file`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}download_from_url",
            mapOf("location_id" to DOWNLOADS, "path" to "e2e-404.txt", "url" to "$fixtureBaseUrl/missing.txt"),
        )
        assertEquals(true, result.isError)
        assertFalse(
            toolText(result).contains("not allowed", ignoreCase = true),
            "must fail on the HTTP error, not the http-disabled gate: ${toolText(result)}",
        )
        assertFalse(fileNames(callListFiles(DOWNLOADS)).contains("e2e-404.txt"))
    }

    @Test
    @Order(25)
    fun `download_from_url connection refused returns error`() = runBlocking {
        val refusedUrl = fixtureBaseUrl.replace(":${FixtureHttpServer.FIXTURE_HTTP_PORT}", ":18999")
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}download_from_url",
            mapOf("location_id" to DOWNLOADS, "path" to "e2e-refused.txt", "url" to "$refusedUrl/x.txt"),
        )
        assertEquals(true, result.isError)
        assertFalse(fileNames(callListFiles(DOWNLOADS)).contains("e2e-refused.txt"))
    }

    @Test
    @Order(26)
    fun `download_from_url times out on slow server`() = runBlocking {
        val start = System.currentTimeMillis()
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}download_from_url",
            mapOf("location_id" to DOWNLOADS, "path" to "e2e-slow.txt", "url" to "$fixtureBaseUrl/slow.txt"),
        )
        val elapsed = System.currentTimeMillis() - start
        assertEquals(true, result.isError, toolText(result))
        assertTrue(
            elapsed < FixtureHttpServer.SLOW_DELAY_MS + 5_000,
            "timeout must trigger before the slow endpoint responds (elapsed=${elapsed}ms)",
        )
        assertFalse(fileNames(callListFiles(DOWNLOADS)).contains("e2e-slow.txt"))
    }

    @Test
    @Order(27)
    fun `download_from_url handles truncated response`() = runBlocking<Unit> {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}download_from_url",
            mapOf(
                "location_id" to DOWNLOADS,
                "path" to "e2e-truncated.txt",
                "url" to "$fixtureBaseUrl/truncated.txt",
            ),
        )
        // Observed redroid 13 (API 33) and 14 (API 34) behavior — documents the platform, revisit on image upgrade:
        // the mid-stream cut fails cleanly ("unexpected end of stream") and the pending
        // MediaStore row is deleted — no partial file remains in the listing.
        assertEquals(true, result.isError)
        assertTrue(toolText(result).contains("unexpected end of stream"), toolText(result))
        assertFalse(fileNames(callListFiles(DOWNLOADS)).contains("e2e-truncated.txt"))
        // Server healthy afterwards
        assertTrue(totalCount(callListFiles(DOWNLOADS)) >= 0)
    }

    @Test
    @Order(28)
    fun `pagination returns stable non-overlapping pages`() {
        val page1 = callListFiles(PICTURES, "bulk", offset = 0, limit = 100)
        val page2 = callListFiles(PICTURES, "bulk", offset = 100, limit = 100)
        val page3 = callListFiles(PICTURES, "bulk", offset = 200, limit = 100)
        for (page in listOf(page1, page2, page3)) {
            assertEquals(BULK_COUNT, totalCount(page))
        }
        assertEquals(100, fileNames(page1).size)
        assertEquals(100, fileNames(page2).size)
        assertEquals(10, fileNames(page3).size)
        assertTrue(hasMore(page1))
        assertTrue(hasMore(page2))
        assertFalse(hasMore(page3))
        val union = fileNames(page1) + fileNames(page2) + fileNames(page3)
        assertEquals(BULK_COUNT, union.size)
        assertEquals(BULK_COUNT, union.toSet().size, "pages must not overlap")
    }

    @Test
    @Order(29)
    fun `pagination offset beyond end returns empty page`() {
        val page = callListFiles(PICTURES, "bulk", offset = 1000, limit = 100)
        assertEquals(0, fileNames(page).size)
        assertFalse(hasMore(page))
        assertEquals(BULK_COUNT, totalCount(page))
    }

    @Test
    @Order(30)
    fun `limit above cap is coerced to 200`() {
        val page = callListFiles(PICTURES, "bulk", offset = 0, limit = 500)
        assertEquals(200, fileNames(page).size)
        assertTrue(hasMore(page))
    }

    @Test
    @Order(31)
    fun `newly seeded file appears after scan`() {
        StorageE2E.seedFile("Pictures/late.jpg", "late")
        StorageE2E.scanPath("Pictures/late.jpg")
        assertTrue(plainFileNames(callListFiles(PICTURES)).contains("late.jpg"))
    }

    @Test
    @Order(32)
    fun `download_from_url refuses a local network address when the block is on`() = runBlocking {
        // The fixture is reached through the container gateway, a private address: blocked with the switch on.
        StorageE2E.configureBlockLocalNetworkDownloads(true)
        try {
            val result = mcpClient.callTool(
                "${TOOL_PREFIX}download_from_url",
                mapOf("location_id" to DOWNLOADS, "path" to "e2e-blocked.txt", "url" to "$fixtureBaseUrl/fixture.txt"),
            )
            assertEquals(true, result.isError, toolText(result))
            assertTrue(toolText(result).contains("Block Local Network Downloads"), toolText(result))
            assertFalse(fileNames(callListFiles(DOWNLOADS)).contains("e2e-blocked.txt"))
        } finally {
            StorageE2E.configureBlockLocalNetworkDownloads(false)
        }
    }

    /** A raw MCP POST (no SDK client), so a test can abort it or send a notification of its own. */
    private fun mcpPost(body: String): Request =
        Request
            .Builder()
            .url("${SharedAndroidContainer.mcpServerUrl}/mcp")
            .header("Authorization", "Bearer ${AndroidContainerSetup.E2E_BEARER_TOKEN}")
            .header("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    /** A download of the fixture's /stall.bin into [path], as a raw tools/call with JSON-RPC id [id]. */
    private fun stallDownload(
        id: Int,
        path: String,
    ): Request {
        val arguments = """{"location_id":"$DOWNLOADS","path":"$path","url":"$fixtureBaseUrl/stall.bin"}"""
        return mcpPost(
            """{"jsonrpc":"2.0","id":$id,"method":"tools/call",""" +
                """"params":{"name":"${TOOL_PREFIX}download_from_url","arguments":$arguments}}""",
        )
    }

    /** Waits for the device to hang up on /stall.bin, at most [STALL_PROMPT_STOP_MS] after [cancelledAt]. */
    private fun assertDeviceStoppedDownloading(cancelledAt: Long) {
        assertTrue(
            fixtureServer.stallClientGone.await(STALL_STOP_TIMEOUT_S, TimeUnit.SECONDS),
            "the device kept downloading after the tool call was cancelled",
        )
        val stoppedAfterMs = System.currentTimeMillis() - cancelledAt
        assertTrue(stoppedAfterMs < STALL_PROMPT_STOP_MS, "the download stopped ${stoppedAfterMs}ms after the cancel")
    }

    /**
     * The device's Download directory entries named [name], hidden ones included: a download's file stays pending
     * (`.pending-<expiry>-<name>` on disk, absent from `list_files`) until it completes.
     */
    private fun downloadDirEntries(name: String): List<String> =
        StorageE2E
            .shell("ls -a /sdcard/Download")
            .lines()
            .map { it.trim() }
            .filter { it.endsWith(name) }

    /** The stalled download is writing its file: waits for it to appear in the Download directory. */
    private fun assertDownloadFileExists(name: String) {
        val deadline = System.currentTimeMillis() + STALL_PROMPT_STOP_MS
        while (downloadDirEntries(name).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue(downloadDirEntries(name).isNotEmpty(), "the download's file never appeared")
    }

    /** The cancelled download's file is deleted as it unwinds: waits for it to leave the Download directory. */
    private fun assertNoFileLeft(name: String) {
        val deadline = System.currentTimeMillis() + STALL_PROMPT_STOP_MS
        while (downloadDirEntries(name).isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals(emptyList<String>(), downloadDirEntries(name))
        assertFalse(fileNames(callListFiles(DOWNLOADS)).contains(name))
    }

    @Test
    @Order(33)
    fun `download_from_url stops at once and leaves no file when its client closes the request`() {
        fixtureServer.resetStall()
        val call = OkHttpClient().newCall(stallDownload(id = 1, path = "e2e-closed.bin"))
        val requestThread = thread { runCatching { call.execute().close() } }
        assertTrue(
            fixtureServer.stallStarted.await(STALL_START_TIMEOUT_S, TimeUnit.SECONDS),
            "the device never started the download",
        )
        assertDownloadFileExists("e2e-closed.bin")

        // The MCP client gives up: it closes its HTTP request
        val cancelledAt = System.currentTimeMillis()
        call.cancel()

        assertDeviceStoppedDownloading(cancelledAt)
        requestThread.join(STALL_PROMPT_STOP_MS)
        assertNoFileLeft("e2e-closed.bin")
    }

    @Test
    @Order(34)
    fun `download_from_url stops at once and leaves no file when its client sends notifications cancelled`() {
        fixtureServer.resetStall()
        val answer = AtomicReference<String>()
        val requestThread =
            thread {
                runCatching {
                    OkHttpClient().newCall(stallDownload(id = CANCELLED_ID, path = "e2e-cancelled.bin")).execute().use {
                        answer.set(it.body.string())
                    }
                }
            }
        assertTrue(
            fixtureServer.stallStarted.await(STALL_START_TIMEOUT_S, TimeUnit.SECONDS),
            "the device never started the download",
        )
        assertDownloadFileExists("e2e-cancelled.bin")

        // The MCP client cancels the call with the protocol's notification, keeping its request open
        val cancelledAt = System.currentTimeMillis()
        val notification =
            """{"jsonrpc":"2.0","method":"notifications/cancelled",""" +
                """"params":{"requestId":$CANCELLED_ID,"reason":"e2e"}}"""
        OkHttpClient().newCall(mcpPost(notification)).execute().use { assertEquals(HTTP_ACCEPTED, it.code) }

        assertDeviceStoppedDownloading(cancelledAt)
        requestThread.join(STALL_PROMPT_STOP_MS)
        // The open request is answered, with the error result of a cancelled call
        assertTrue(answer.get().orEmpty().contains("The client cancelled this tool call"), answer.get())
        assertNoFileLeft("e2e-cancelled.bin")
    }
}
