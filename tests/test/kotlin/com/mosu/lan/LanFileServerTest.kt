package com.mosu.lan

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanFileServerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: LanFileServer
    private lateinit var beatmapSet: File

    @Before
    fun setUp() {
        beatmapSet = folder.newFolder("123 Artist - Title").apply {
            resolve("map.osu").writeText("osu file format v14")
            resolve("audio.mp3").writeBytes(ByteArray(200_000) { it.toByte() })
            resolve("video.mp4").writeBytes(ByteArray(1000))
            resolve("sb").mkdir()
            resolve("sb/star.png").writeBytes(ByteArray(10))
        }

        server = LanFileServer(
            findBeatmapSet = { md5 -> if (md5 == MD5) beatmapSet else null },
            cacheDirectory = folder.newFolder("cache"),
            port = 0,
        ).also { it.start() }
    }

    @After
    fun tearDown() = server.stop()

    private fun request(md5: String): Pair<JSONObject, ByteArray> = Socket("127.0.0.1", server.port).use { socket ->
        socket.getOutputStream().write((JSONObject().put("md5", md5).toString() + "\n").toByteArray())

        val input = BufferedInputStream(socket.getInputStream())
        val header = JSONObject(LanProtocol.readFrame(input)!!)

        header to input.readBytes()
    }

    @Test
    fun `beatmap set is sent as an archive without videos`() {
        val (header, body) = request(MD5)

        assertTrue(header.getBoolean("ok"))
        assertEquals("123 Artist - Title", header.getString("name"))
        assertEquals(header.getLong("size"), body.size.toLong())

        val entries = mutableMapOf<String, Int>()
        ZipInputStream(ByteArrayInputStream(body)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().size
            }
        }

        assertEquals(setOf("map.osu", "audio.mp3", "sb/star.png"), entries.keys)
        assertEquals(200_000, entries["audio.mp3"])
    }

    @Test
    fun `missing beatmap is refused with a reason`() {
        val (header, body) = request("0".repeat(32))

        assertFalse(header.getBoolean("ok"))
        assertEquals("The room's device doesn't have this beatmap", header.getString("error"))
        assertEquals(0, body.size)
    }

    @Test
    fun `malformed request is refused`() {
        val (header, _) = request("../../etc/passwd")

        assertFalse(header.getBoolean("ok"))
    }


    private companion object {
        const val MD5 = "0123456789abcdef0123456789abcdef"
    }
}
