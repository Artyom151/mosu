package com.mosu.lan

import android.util.Log
import com.osudroid.multiplayer.api.data.PlayerStatus
import com.osudroid.multiplayer.api.data.RoomStatus
import com.osudroid.multiplayer.api.data.RoomTeam
import com.osudroid.multiplayer.api.data.TeamMode
import com.osudroid.multiplayer.api.data.WinCondition
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * A multiplayer room hosted on this device, reproducing the behavior of the online multiplayer server for the clients
 * in the local network.
 *
 * All room state is owned by a single thread ([executor]); sockets are read on their own threads and hand events over.
 *
 * @param ownerUid The uid of the local player creating the room, who becomes the first host.
 */
class LanRoomServer(
    roomName: String,
    password: String?,
    maxPlayers: Int,
    private val ownerUid: Long,
    port: Int = LanProtocol.ROOM_PORT,
) {

    private class Player(val uid: Long, val username: String) {
        var status = PlayerStatus.NotReady
        var team: RoomTeam? = null
        var mods = JSONArray()
    }

    private inner class Connection(val socket: Socket) {

        @Volatile
        var uid: Long? = null

        @Volatile
        var isClosed = false

        fun send(frame: ByteArray) {
            if (isClosed) return

            try {
                synchronized(this) {
                    socket.getOutputStream().apply {
                        write(frame)
                        flush()
                    }
                }
            } catch (_: IOException) {
                close()
            }
        }

        fun send(event: String, vararg args: Any?) = send(LanProtocol.encode(event, args))

        fun close() {
            isClosed = true
            runCatching { socket.close() }
        }
    }


    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "LanRoomServer").apply { isDaemon = true }
    }

    private val serverSocket = bind(port)

    /**
     * The port the room is listening on.
     */
    val port: Int get() = serverSocket.localPort

    @Volatile
    var isRunning = false
        private set


    // Room state, only accessed from the executor thread.

    private val sessionId = UUID.randomUUID().toString()
    private var name = roomName.ifBlank { "LAN room" }.take(64)
    private var password = password?.takeIf { it.isNotEmpty() }
    private var maxPlayers = maxPlayers.coerceIn(2, MAX_PLAYERS)
    private var mods = JSONArray()
    private var isFreeMod = false
    private var isRemoveSliderLock = false
    private var teamMode = TeamMode.HeadToHead
    private var winCondition = WinCondition.ScoreV1
    private var beatmap: JSONObject? = null
    private var status = RoomStatus.Idle
    private var host = ownerUid

    private val players = LinkedHashMap<Long, Player>()
    private val connections = HashMap<Long, Connection>()

    // Match state.
    private val loadParticipants = HashSet<Long>()
    private val playing = HashSet<Long>()
    private val loaded = HashSet<Long>()
    private val skipped = HashSet<Long>()
    private val submitted = LinkedHashMap<Long, JSONObject>()
    private val liveScores = LinkedHashMap<Long, JSONObject>()
    private var isLoadAnnounced = false
    private var isSkipAnnounced = false
    private var isLiveScoreDirty = false
    private var ticks = 0


    /**
     * A snapshot of the room for discovery announcements, readable from any thread.
     */
    @Volatile
    var advertisement: JSONObject = JSONObject()
        private set


    fun start() {
        isRunning = true
        publishAdvertisement()

        thread(name = "LanRoomServer-accept", isDaemon = true) {
            while (isRunning) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to accept a connection", e)
                    continue
                }

                thread(name = "LanRoomServer-client", isDaemon = true) { serve(socket) }
            }
        }

        executor.scheduleWithFixedDelay(::tick, 1, 1, TimeUnit.SECONDS)
    }

    /**
     * Closes the room, telling every connected player that the server closed it.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        runCatching { serverSocket.close() }

        executor.execute {
            val frame = LanProtocol.encode("disconnect", arrayOf("io server disconnect"))

            for (connection in connections.values) {
                connection.send(frame)
                connection.close()
            }

            connections.clear()
            players.clear()
        }

        executor.shutdown()
    }


    private fun serve(socket: Socket) {
        val connection = Connection(socket)

        try {
            socket.tcpNoDelay = true
            socket.soTimeout = LanProtocol.TIMEOUT_MS

            val input = BufferedInputStream(socket.getInputStream())
            val hello = LanProtocol.readFrame(input) ?: return
            val (event, args) = LanProtocol.decode(hello)

            if (event != "hello" || args.firstOrNull() !is JSONObject) {
                return
            }

            val accepted = executor.submit<Boolean> { onHello(connection, args[0] as JSONObject) }.get()

            if (!accepted) {
                return
            }

            while (isRunning) {
                val frame = LanProtocol.readFrame(input) ?: break
                val (name, arguments) = LanProtocol.decode(frame)

                post { onEvent(connection, name, arguments) }
            }
        } catch (_: Exception) {
            // Connection dropped or sent garbage, handled below.
        } finally {
            connection.close()

            post { onDisconnect(connection) }
        }
    }


    /**
     * Runs [block] on the room thread, unless the room was closed in the meantime.
     */
    private fun post(block: () -> Unit) {
        if (!isRunning) return

        try {
            executor.execute { runCatching(block).onFailure { Log.e(TAG, "Room event failed", it) } }
        } catch (_: RejectedExecutionException) {
            // The room was closed concurrently.
        }
    }

    private fun onHello(connection: Connection, auth: JSONObject): Boolean {

        fun reject(reason: String): Boolean {
            connection.send("connect_error", reason)
            return false
        }

        val uid = auth.optString("uid").toLongOrNull() ?: return reject("Invalid player")

        if (auth.optInt("version") != LanProtocol.VERSION) {
            return reject("This room uses a different mosu version")
        }

        val existing = players[uid]

        if (existing == null && password != null && auth.optString("password") != password) {
            return reject("Wrong room password")
        }

        if (existing == null && players.size >= maxPlayers) {
            return reject("The room is full")
        }

        // A reconnecting player replaces their previous connection.
        connections[uid]?.takeIf { it !== connection }?.close()

        connection.uid = uid
        connections[uid] = connection

        val player = existing ?: Player(uid, auth.optString("username").trim().take(32).ifEmpty { "Player" }).also {
            if (teamMode == TeamMode.TeamVS) it.team = RoomTeam.Red
            players[uid] = it
        }

        connection.send("initialConnection", roomJson())

        if (existing == null) {
            broadcast("playerJoined", playerJson(player), except = uid)
        }

        publishAdvertisement()
        return true
    }

    private fun onDisconnect(connection: Connection) {
        val uid = connection.uid ?: return

        // The player reconnected through another connection in the meantime.
        if (connections[uid] !== connection) return

        connections.remove(uid)
        players.remove(uid) ?: return

        broadcast("playerLeft", uid.toString())

        if (host == uid && players.isNotEmpty()) {
            host = players.keys.first()
            broadcast("hostChanged", host.toString())
        }

        checkMatchBarriers()
        publishAdvertisement()
    }


    private fun onEvent(connection: Connection, event: String, args: Array<Any?>) {
        val uid = connection.uid ?: return
        val player = players[uid] ?: return
        val isHost = uid == host
        val arg = args.firstOrNull()

        when (event) {

            "ping" -> Unit

            // Host-only events, echoed to everyone.

            "beatmapChanged" -> if (isHost && status != RoomStatus.Playing) {
                beatmap = (arg as? JSONObject)?.takeIf { it.has("md5") && !it.isNull("md5") }
                status = if (beatmap == null) RoomStatus.ChangingBeatmap else RoomStatus.Idle
                broadcast("beatmapChanged", beatmap)
            }

            "hostChanged" -> if (isHost) {
                val target = arg?.toString()?.toLongOrNull()?.takeIf { it in players } ?: return
                host = target
                broadcast("hostChanged", target.toString())
            }

            "playerKicked" -> if (isHost) {
                val target = arg?.toString()?.toLongOrNull()?.takeIf { it != uid && it in players } ?: return

                broadcast("playerKicked", target.toString())
                players.remove(target)

                // Give the kicked player a moment to receive the event before closing.
                connections.remove(target)?.let { executor.schedule({ it.close() }, 500, TimeUnit.MILLISECONDS) }

                checkMatchBarriers()
            }

            "roomModsChanged" -> if (isHost) {
                mods = arg as? JSONArray ?: JSONArray()
                broadcast("roomModsChanged", mods)
            }

            "roomGameplaySettingsChanged" -> if (isHost) {
                val json = arg as? JSONObject ?: return

                if (json.has("isFreeMod")) isFreeMod = json.optBoolean("isFreeMod")
                if (json.has("isRemoveSliderLock")) isRemoveSliderLock = json.optBoolean("isRemoveSliderLock")

                broadcast("roomGameplaySettingsChanged", gameplaySettingsJson())
            }

            "teamModeChanged" -> if (isHost) {
                teamMode = TeamMode.fromWire(arg.toString()) ?: return
                broadcast("teamModeChanged", teamMode.name)

                // Head-to-head clears teams, team VS starts everyone in the red team.
                for (p in players.values) {
                    p.team = if (teamMode == TeamMode.TeamVS) RoomTeam.Red else null
                    broadcast("teamChanged", p.uid.toString(), p.team?.name)
                }
            }

            "winConditionChanged" -> if (isHost) {
                winCondition = WinCondition.fromWire(arg.toString()) ?: return
                broadcast("winConditionChanged", winCondition.name)
            }

            "roomNameChanged" -> if (isHost) {
                name = arg?.toString()?.trim()?.take(64)?.ifEmpty { null } ?: return
                broadcast("roomNameChanged", name)
                publishAdvertisement()
            }

            "maxPlayersChanged" -> if (isHost) {
                val value = arg?.toString()?.toIntOrNull() ?: return
                maxPlayers = value.coerceIn(maxOf(2, players.size), MAX_PLAYERS)
                broadcast("maxPlayersChanged", maxPlayers.toString())
                publishAdvertisement()
            }

            "roomPasswordChanged" -> if (isHost) {
                password = arg?.toString()?.takeIf { it.isNotEmpty() }
                publishAdvertisement()
            }

            "playBeatmap" -> if (isHost && status != RoomStatus.Playing && beatmap != null) {
                startMatch()
            }

            // Player events, echoed with the player's uid prepended.

            "playerStatusChanged" -> {
                player.status = PlayerStatus.fromWire(arg.toString()) ?: return
                broadcast("playerStatusChanged", uid.toString(), player.status.name)
            }

            "teamChanged" -> if (teamMode == TeamMode.TeamVS) {
                player.team = RoomTeam.fromWire(arg.toString()) ?: return
                broadcast("teamChanged", uid.toString(), player.team?.name)
            }

            "playerModsChanged" -> {
                player.mods = arg as? JSONArray ?: JSONArray()
                broadcast("playerModsChanged", uid.toString(), player.mods)
            }

            "chatMessage" -> {
                val message = arg?.toString()?.take(500)?.takeIf { it.isNotBlank() } ?: return
                broadcast("chatMessage", uid.toString(), message)
            }

            // Match flow.

            "beatmapLoadComplete" -> if (status == RoomStatus.Playing) {
                loaded.add(uid)
                checkMatchBarriers()
            }

            "skipRequested" -> if (status == RoomStatus.Playing) {
                skipped.add(uid)
                checkMatchBarriers()
            }

            "liveScoreData" -> if (status == RoomStatus.Playing) {
                val json = arg as? JSONObject ?: return

                liveScores[uid] = JSONObject()
                    .put("uid", uid.toString())
                    .put("username", player.username)
                    .put("score", json.optLong("score"))
                    .put("combo", json.optInt("combo"))
                    .put("accuracy", json.optDouble("accuracy", 0.0))
                    .put("isAlive", json.optBoolean("isAlive", true))

                isLiveScoreDirty = true
            }

            "scoreSubmission" -> if (status == RoomStatus.Playing && uid in playing) {
                val json = arg as? JSONObject ?: return

                submitted[uid] = json.put("uid", uid).put("username", player.username)
                checkMatchBarriers()
            }
        }
    }


    private fun startMatch() {
        status = RoomStatus.Playing

        loadParticipants.clear()
        playing.clear()
        loaded.clear()
        skipped.clear()
        submitted.clear()
        liveScores.clear()
        isLoadAnnounced = false
        isSkipAnnounced = false
        isLiveScoreDirty = false

        // Players without the beatmap acknowledge the load right away but don't play.
        loadParticipants.addAll(players.keys)

        for (p in players.values) {
            if (p.status != PlayerStatus.MissingBeatmap) {
                playing.add(p.uid)
                p.status = PlayerStatus.Playing
                broadcast("playerStatusChanged", p.uid.toString(), p.status.name)
            }
        }

        broadcast("playBeatmap")
        publishAdvertisement()
    }

    private fun checkMatchBarriers() {
        if (status != RoomStatus.Playing) return

        val connected = connections.keys

        if (!isLoadAnnounced && loadParticipants.filter { it in connected }.all { it in loaded }) {
            isLoadAnnounced = true
            broadcast("allPlayersBeatmapLoadComplete")
        }

        val active = playing.filter { it in connected }

        if (!isSkipAnnounced && active.isNotEmpty() && active.all { it in skipped }) {
            isSkipAnnounced = true
            broadcast("allPlayersSkipRequested")
        }

        if (active.all { it in submitted }) {
            finishMatch()
        }
    }

    private fun finishMatch() {
        val results = JSONArray(submitted.values.sortedWith(resultComparator("maxCombo")))

        // The room state is updated before anyone is told the match ended.
        status = if (beatmap == null) RoomStatus.ChangingBeatmap else RoomStatus.Idle
        publishAdvertisement()

        broadcast("allPlayersScoreSubmitted", results)

        for (uid in playing) {
            val p = players[uid] ?: continue
            p.status = PlayerStatus.NotReady
            broadcast("playerStatusChanged", uid.toString(), p.status.name)
        }

        playing.clear()
        liveScores.clear()
        publishAdvertisement()
    }

    private fun resultComparator(comboKey: String): Comparator<JSONObject> = when (winCondition) {
        WinCondition.Accuracy -> compareByDescending { it.optDouble("accuracy", 0.0) }
        WinCondition.MaxCombo -> compareByDescending { it.optInt(comboKey) }
        else -> compareByDescending { it.optLong("score") }
    }

    private fun tick() {
        ticks++

        if (status == RoomStatus.Playing && isLiveScoreDirty) {
            isLiveScoreDirty = false
            broadcast("liveScoreData", JSONArray(liveScores.values.sortedWith(resultComparator("combo"))))
        }

        if (ticks % (LanProtocol.PING_INTERVAL_MS / 1000).toInt() == 0) {
            broadcast("ping")
        }
    }


    private fun broadcast(event: String, vararg args: Any?, except: Long? = null) {
        val frame = LanProtocol.encode(event, args)

        for ((uid, connection) in connections) {
            if (uid != except) connection.send(frame)
        }
    }

    private fun roomJson() = JSONObject()
        .put("id", ROOM_ID.toString())
        .put("name", name)
        .put("isLocked", password != null)
        .put("maxPlayers", maxPlayers)
        .put("mods", mods)
        .put("gameplaySettings", gameplaySettingsJson())
        .put("teamMode", teamMode.name)
        .put("winCondition", winCondition.name)
        .put("sessionId", sessionId)
        .put("host", JSONObject().put("id", host.toString()))
        .put("beatmap", beatmap ?: JSONObject.NULL)
        .put("status", status.name)
        .put("players", JSONArray(players.values.map(::playerJson)))

    private fun playerJson(player: Player) = JSONObject()
        .put("id", player.uid.toString())
        .put("username", player.username)
        .put("rank", 0)
        .put("status", player.status.name)
        .put("team", player.team?.name ?: JSONObject.NULL)
        .put("mods", player.mods)

    private fun gameplaySettingsJson() = JSONObject()
        .put("isFreeMod", isFreeMod)
        .put("isRemoveSliderLock", isRemoveSliderLock)

    private fun publishAdvertisement() {
        val beatmap = beatmap

        advertisement = JSONObject()
            .put("app", LanProtocol.DISCOVERY_APP)
            .put("version", LanProtocol.VERSION)
            .put("port", port)
            .put("name", name)
            .put("host", players[host]?.username ?: "")
            .put("players", players.size)
            .put("maxPlayers", maxPlayers)
            .put("locked", password != null)
            .put("playing", status == RoomStatus.Playing)
            .put("beatmap", beatmap?.let { "${it.optString("artist")} - ${it.optString("title")} [${it.optString("version")}]" } ?: JSONObject.NULL)
    }


    companion object {
        private const val TAG = "LanRoomServer"

        const val ROOM_ID = 1L
        const val MAX_PLAYERS = 16

        private fun bind(port: Int): ServerSocket {
            val socket = ServerSocket()
            socket.reuseAddress = true

            try {
                socket.bind(InetSocketAddress(port))
            } catch (_: IOException) {
                // The default port is taken (e.g. another room on this device), fall back to any free port.
                socket.bind(InetSocketAddress(0))
            }

            return socket
        }
    }
}
