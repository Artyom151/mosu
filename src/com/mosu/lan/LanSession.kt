package com.mosu.lan

import androidx.preference.PreferenceManager
import com.osudroid.multiplayer.Multiplayer
import com.osudroid.multiplayer.api.RoomAPI
import ru.nsu.ccfit.zuev.osu.menu.LoadingScreen
import com.osudroid.utils.async
import com.osudroid.utils.mainThread
import com.osudroid.utils.updateThread
import io.socket.client.Socket
import kotlin.random.Random
import org.json.JSONObject
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ToastLogger

/**
 * State of LAN multiplayer: the local player's identity, the room being joined and, when hosting, the room server.
 */
object LanSession {

    /**
     * Whether the multiplayer room is a LAN room. Room connections go through [createSocket] while this is true.
     */
    @JvmStatic
    @Volatile
    var isActive = false
        private set

    private var server: LanRoomServer? = null
    private var broadcaster: LanBroadcaster? = null

    private var address = ""
    private var port = 0

    /**
     * Whether this device hosts the room.
     */
    val isHosting get() = server != null

    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(GlobalManager.getInstance().mainActivity)

    /**
     * The local player's identifier in LAN rooms. It's random, persisted, and kept far from online user IDs.
     */
    @JvmStatic
    val uid: Long
        get() {
            val stored = preferences.getLong("lanUid", 0L)

            if (stored > 0) return stored

            return Random.nextLong(LAN_UID_MIN, LAN_UID_MAX).also { preferences.edit().putLong("lanUid", it).apply() }
        }

    var username: String
        get() = preferences.getString("lanUsername", null)?.takeIf { it.isNotBlank() }
            ?: Config.getOnlineUsername()?.takeIf { it.isNotBlank() }
            ?: "Player"
        set(value) = preferences.edit().putString("lanUsername", value.trim().take(32)).apply()


    /**
     * Hosts a new room on this device and joins it.
     */
    fun host(roomName: String, password: String?, maxPlayers: Int) {
        stopHosting()

        async {
            try {
                val server = LanRoomServer(roomName, password, maxPlayers, uid).also { it.start() }
                LanSession.server = server
                broadcaster = LanBroadcaster { server.advertisement }.also { it.start() }

                join("127.0.0.1", server.port, password)
            } catch (e: Exception) {
                stopHosting()
                ToastLogger.showText("Couldn't create the LAN room: ${e.message}", true)
            }
        }
    }

    /**
     * Joins the room at [address]:[port].
     */
    fun join(address: String, port: Int, password: String?) {
        this.address = address
        this.port = port
        isActive = true

        val global = GlobalManager.getInstance()
        global.songService.isGaming = true
        Multiplayer.isMultiplayer = true

        async {
            LoadingScreen().show()

            global.mainActivity.checkNewSkins()
            global.mainActivity.loadBeatmapLibrary()

            RoomAPI.connectToRoom(
                roomId = LanRoomServer.ROOM_ID,
                userId = uid,
                gameSessionId = "",
                roomPassword = password
            )
        }
    }

    /**
     * Creates the socket used by [RoomAPI] to connect to the LAN room.
     */
    fun createSocket(password: String?): Socket = LanSocket(
        address = address,
        port = port,
        auth = JSONObject()
            .put("uid", uid.toString())
            .put("username", username)
            .put("password", password ?: "")
            .put("version", LanProtocol.VERSION),
        onConnectFailed = { reason ->
            // Failures after the room was joined are handled by the room scene (reconnection).
            if (Multiplayer.roomScene == null) {
                ToastLogger.showText("Couldn't join the LAN room: $reason", true)
                updateThread { leave() }
            }
        }
    )

    /**
     * Leaves LAN multiplayer, closing the room if this device hosts it, and goes back to the LAN lobby.
     *
     * Must be called on the update thread.
     */
    fun leave() {
        stopHosting()
        isActive = false

        Multiplayer.isMultiplayer = false
        GlobalManager.getInstance().songService.isGaming = false
        GlobalManager.getInstance().mainScene.show()

        mainThread { LanLobby().show() }
    }

    private fun stopHosting() {
        broadcaster?.stop()
        broadcaster = null

        server?.stop()
        server = null
    }


    private const val LAN_UID_MIN = 1_000_000_000_000L
    private const val LAN_UID_MAX = 9_000_000_000_000L
}
