package com.mosu.lan

import io.socket.client.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanRoomServerTest {

    private lateinit var server: LanRoomServer
    private val clients = mutableListOf<Client>()

    /**
     * A connected room client recording every event it receives.
     */
    private inner class Client(uid: Long, name: String, password: String? = null) {
        val events = LinkedBlockingQueue<Pair<String, Array<out Any?>>>()

        val socket = LanSocket(
            "127.0.0.1", server.port,
            JSONObject().put("uid", uid.toString()).put("username", name).put("password", password ?: "").put("version", LanProtocol.VERSION)
        ).apply {
            for (event in EVENTS) on(event) { args -> events.add(event to args) }
        }

        init {
            clients.add(this)
            socket.connect()
        }

        fun emit(event: String, vararg args: Any?) = socket.emit(event, *args)

        /**
         * Waits for [event], skipping any other event received before it.
         */
        fun await(event: String): Array<out Any?> {
            val deadline = System.currentTimeMillis() + 5000

            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                val (name, args) = events.poll(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                    ?: throw AssertionError("Timed out waiting for '$event'")

                if (name == event) return args
            }
        }
    }

    @Before
    fun setUp() {
        server = LanRoomServer("Test room", null, 8, ownerUid = HOST, port = 0).also { it.start() }
    }

    @After
    fun tearDown() {
        clients.forEach { it.socket.disconnect() }
        server.stop()
    }


    @Test
    fun `players join and see each other`() {
        val host = Client(HOST, "Host")
        val room = host.await("initialConnection")[0] as JSONObject

        assertEquals("Test room", room.getString("name"))
        assertEquals(HOST.toString(), room.getJSONObject("host").getString("id"))
        assertEquals(1, room.getJSONArray("players").length())

        val guest = Client(GUEST, "Guest")
        val guestRoom = guest.await("initialConnection")[0] as JSONObject
        assertEquals(2, guestRoom.getJSONArray("players").length())

        val joined = host.await("playerJoined")[0] as JSONObject
        assertEquals(GUEST.toString(), joined.getString("id"))
        assertEquals("Guest", joined.getString("username"))

        assertEquals(2, server.advertisement.getInt("players"))
    }

    @Test
    fun `chat and player events are echoed with the sender uid`() {
        val host = Client(HOST, "Host").also { it.await("initialConnection") }
        val guest = Client(GUEST, "Guest").also { it.await("initialConnection") }

        guest.emit("chatMessage", "hello")
        val chat = host.await("chatMessage")
        assertEquals(GUEST.toString(), chat[0])
        assertEquals("hello", chat[1])

        guest.emit("playerStatusChanged", "Ready")
        val status = host.await("playerStatusChanged")
        assertEquals(GUEST.toString(), status[0])
        assertEquals("Ready", status[1])
    }

    @Test
    fun `host only events are ignored from other players`() {
        val host = Client(HOST, "Host").also { it.await("initialConnection") }
        val guest = Client(GUEST, "Guest").also { it.await("initialConnection") }

        guest.emit("roomNameChanged", "Hijacked")
        host.emit("roomNameChanged", "Renamed")

        assertEquals("Renamed", guest.await("roomNameChanged")[0])
    }

    @Test
    fun `wrong password is refused`() {
        server.stop()
        server = LanRoomServer("Locked", "secret", 8, ownerUid = HOST, port = 0).also { it.start() }

        val refused = Client(GUEST, "Guest", password = "nope")
        assertEquals("Wrong room password", refused.await(Socket.EVENT_CONNECT_ERROR)[0])

        val accepted = Client(HOST, "Host", password = "secret")
        assertNotNull(accepted.await("initialConnection"))
    }

    @Test
    fun `a match goes through load, skip and results`() {
        val host = Client(HOST, "Host").also { it.await("initialConnection") }
        val guest = Client(GUEST, "Guest").also { it.await("initialConnection") }

        host.emit("beatmapChanged", JSONObject().put("md5", "abc").put("title", "Song").put("artist", "Artist").put("version", "Hard"))
        assertEquals("abc", (guest.await("beatmapChanged")[0] as JSONObject).getString("md5"))

        host.emit("playBeatmap")
        guest.await("playBeatmap")

        host.emit("beatmapLoadComplete")
        guest.emit("beatmapLoadComplete")
        host.await("allPlayersBeatmapLoadComplete")

        host.emit("skipRequested")
        guest.emit("skipRequested")
        guest.await("allPlayersSkipRequested")

        guest.emit("liveScoreData", JSONObject().put("score", 500).put("combo", 10).put("accuracy", 0.9).put("isAlive", true))
        val live = host.await("liveScoreData")[0] as JSONArray
        assertEquals("Guest", live.getJSONObject(0).getString("username"))

        host.emit("scoreSubmission", JSONObject().put("score", 1000).put("username", "spoofed").put("isAlive", true))
        guest.emit("scoreSubmission", JSONObject().put("score", 2000).put("username", "Guest").put("isAlive", true))

        val results = host.await("allPlayersScoreSubmitted")[0] as JSONArray
        assertEquals(2, results.length())
        assertEquals("Guest", results.getJSONObject(0).getString("username"))
        assertEquals("Host", results.getJSONObject(1).getString("username"))
        assertFalse(server.advertisement.getBoolean("playing"))
    }

    @Test
    fun `leaving host passes the room to the next player`() {
        val host = Client(HOST, "Host").also { it.await("initialConnection") }
        val guest = Client(GUEST, "Guest").also { it.await("initialConnection") }

        host.socket.disconnect()

        assertEquals(HOST.toString(), guest.await("playerLeft")[0])
        assertEquals(GUEST.toString(), guest.await("hostChanged")[0])
    }

    @Test
    fun `kicked player is told and removed`() {
        val host = Client(HOST, "Host").also { it.await("initialConnection") }
        val guest = Client(GUEST, "Guest").also { it.await("initialConnection") }

        host.emit("playerKicked", GUEST.toString())

        assertEquals(GUEST.toString(), guest.await("playerKicked")[0])
        assertEquals(GUEST.toString(), host.await("playerKicked")[0])
        assertTrue(guest.await(Socket.EVENT_DISCONNECT).isNotEmpty())
    }

    @Test
    fun `room announcements are parsed`() {
        val ad = LanScanner.parse(server.advertisement.toString(), "192.168.43.1")!!

        assertEquals("Test room", ad.name)
        assertEquals(server.port, ad.port)
        assertEquals("192.168.43.1:${server.port}", ad.key)
        assertEquals(null, LanScanner.parse("{\"app\":\"other\"}", "1.2.3.4"))
    }


    private companion object {
        const val HOST = 1_000_000_000_001L
        const val GUEST = 1_000_000_000_002L

        val EVENTS = listOf(
            "initialConnection", "playerJoined", "playerLeft", "playerKicked", "hostChanged", "chatMessage",
            "playerStatusChanged", "roomNameChanged", "beatmapChanged", "playBeatmap", "allPlayersBeatmapLoadComplete",
            "allPlayersSkipRequested", "liveScoreData", "allPlayersScoreSubmitted", Socket.EVENT_CONNECT_ERROR,
            Socket.EVENT_DISCONNECT,
        )
    }
}
