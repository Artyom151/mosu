package com.mosu.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * A room found in the local network.
 */
data class LanRoomAd(
    val address: String,
    val port: Int,
    val name: String,
    val host: String,
    val players: Int,
    val maxPlayers: Int,
    val isLocked: Boolean,
    val isPlaying: Boolean,
    val beatmap: String?,
) {
    val key get() = "$address:$port"
}

/**
 * Announces a hosted room by broadcasting [LanRoomServer.advertisement] every second on every network interface, so
 * it's found both on a shared Wi-Fi network and on a hotspot created by one of the players.
 */
class LanBroadcaster(private val advertisement: () -> JSONObject) {

    @Volatile
    private var isRunning = false

    fun start() {
        isRunning = true

        thread(name = "LanBroadcaster", isDaemon = true) {
            DatagramSocket().use { socket ->
                socket.broadcast = true

                while (isRunning) {
                    val payload = advertisement().toString().toByteArray(Charsets.UTF_8)

                    for (target in broadcastAddresses()) {
                        try {
                            socket.send(DatagramPacket(payload, payload.size, target, LanProtocol.DISCOVERY_PORT))
                        } catch (_: Exception) {
                            // Interface went down or doesn't allow broadcasts.
                        }
                    }

                    Thread.sleep(1000)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))

        try {
            for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                for (address in networkInterface.interfaceAddresses) {
                    address.broadcast?.let(result::add)
                }
            }
        } catch (e: Exception) {
            Log.w("LanBroadcaster", "Couldn't list network interfaces", e)
        }

        return result
    }
}

/**
 * Listens for room announcements and reports the rooms currently visible.
 */
class LanScanner(context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    @Volatile
    private var socket: DatagramSocket? = null

    /**
     * Starts scanning; [onRoomsChanged] is called from a background thread whenever the visible rooms change.
     */
    fun start(onRoomsChanged: (List<LanRoomAd>) -> Unit) {
        stop()

        // Some devices drop broadcast packets unless a multicast lock is held.
        val lock = wifiManager?.createMulticastLock("mosu-lan")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(LanProtocol.DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            Log.e("LanScanner", "Couldn't listen for rooms", e)
            lock?.release()
            return
        }

        this.socket = socket

        thread(name = "LanScanner", isDaemon = true) {
            val rooms = LinkedHashMap<String, Pair<LanRoomAd, Long>>()
            val buffer = ByteArray(4096)
            var lastReported = emptyList<LanRoomAd>()

            while (this.socket === socket) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    parse(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress ?: continue)?.let {
                        rooms[it.key] = it to System.currentTimeMillis()
                    }
                } catch (_: SocketTimeoutException) {
                    // Used to prune rooms that stopped announcing.
                } catch (_: Exception) {
                    break
                }

                val now = System.currentTimeMillis()
                rooms.values.removeAll { now - it.second > ROOM_TIMEOUT_MS }

                val current = rooms.values.map { it.first }

                if (current != lastReported) {
                    lastReported = current
                    onRoomsChanged(current)
                }
            }

            runCatching { lock?.release() }
        }
    }

    fun stop() {
        val socket = socket
        this.socket = null
        socket?.close()
    }


    companion object {
        private const val ROOM_TIMEOUT_MS = 4000L

        internal fun parse(payload: String, address: String): LanRoomAd? = try {
            val json = JSONObject(payload)

            if (json.optString("app") != LanProtocol.DISCOVERY_APP || json.optInt("version") != LanProtocol.VERSION) {
                null
            } else LanRoomAd(
                address = address,
                port = json.getInt("port"),
                name = json.optString("name").take(64),
                host = json.optString("host").take(32),
                players = json.optInt("players"),
                maxPlayers = json.optInt("maxPlayers"),
                isLocked = json.optBoolean("locked"),
                isPlaying = json.optBoolean("playing"),
                beatmap = if (json.isNull("beatmap")) null else json.optString("beatmap").take(200),
            )
        } catch (_: Exception) {
            null
        }
    }
}
