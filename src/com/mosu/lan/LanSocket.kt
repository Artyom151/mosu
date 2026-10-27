package com.mosu.lan

import android.util.Log
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import java.net.Socket as TcpSocket

/**
 * A drop-in replacement of the socket.io client socket that talks to a [LanRoomServer] over TCP, so the multiplayer
 * room client works unchanged in LAN rooms.
 *
 * Events received from the room are dispatched to the listeners registered with [on], and [emit] sends events to it.
 *
 * @param auth The `hello` payload: `{uid, username, password, version}`.
 * @param onConnectFailed Called when the room couldn't be joined, before any room state was received.
 */
class LanSocket(
    private val address: String,
    private val port: Int,
    private val auth: JSONObject,
    private val onConnectFailed: (String) -> Unit = {},
    private val onRoomInfo: (JSONObject) -> Unit = {},
) : Socket(null, null, null) {

    private val writer = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "LanSocket-writer").apply { isDaemon = true }
    }

    @Volatile
    private var tcp: TcpSocket? = null

    @Volatile
    private var isClosed = false

    @Volatile
    private var hasJoined = false


    override fun connect(): Socket {
        thread(name = "LanSocket-reader", isDaemon = true) { run() }
        return this
    }

    override fun open() = connect()

    override fun connected() = tcp?.isConnected == true && !isClosed

    override fun emit(event: String, vararg args: Any?): Emitter {
        send(event, args)
        return this
    }

    override fun disconnect(): Socket {
        dispatchDisconnect("io client disconnect")
        return this
    }

    override fun close() = disconnect()


    private fun run() {
        val socket = TcpSocket()

        try {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = LanProtocol.TIMEOUT_MS
            tcp = socket
        } catch (e: IOException) {
            fail("Couldn't reach the room (${e.message})")
            return
        }

        send("hello", arrayOf(auth))
        writer.scheduleWithFixedDelay({ send("ping", emptyArray()) }, LanProtocol.PING_INTERVAL_MS, LanProtocol.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)

        try {
            val input = BufferedInputStream(socket.getInputStream())

            while (!isClosed) {
                val frame = LanProtocol.readFrame(input) ?: break
                val (event, args) = LanProtocol.decode(frame)

                when (event) {
                    "ping" -> Unit
                    "lanInfo" -> (args.firstOrNull() as? JSONObject)?.let(onRoomInfo)
                    "connect_error" -> {
                        fail(args.firstOrNull()?.toString() ?: "Connection refused")
                        return
                    }
                    "disconnect" -> {
                        dispatchDisconnect(args.firstOrNull()?.toString() ?: "io server disconnect")
                        return
                    }
                    else -> {
                        if (event == "initialConnection") hasJoined = true
                        dispatch(event, args)
                    }
                }
            }
        } catch (e: Exception) {
            if (!isClosed) Log.w(TAG, "Connection to the room lost", e)
        }

        if (!isClosed) {
            if (hasJoined) dispatchDisconnect("transport close") else fail("Connection lost")
        }
    }

    private fun send(event: String, args: Array<out Any?>) {
        if (isClosed) return

        val frame = LanProtocol.encode(event, args)

        runCatching {
            writer.execute {
                try {
                    tcp?.getOutputStream()?.apply {
                        write(frame)
                        flush()
                    }
                } catch (_: IOException) {
                    // The reader thread notices the broken connection.
                }
            }
        }
    }

    private fun dispatch(event: String, args: Array<out Any?>) {
        for (listener in listeners(event)) {
            try {
                listener.call(*args)
            } catch (e: Exception) {
                Log.e(TAG, "Listener of '$event' failed", e)
            }
        }
    }

    private fun shutdown(): Boolean {
        if (isClosed) return false
        isClosed = true

        runCatching { tcp?.close() }
        writer.shutdownNow()
        return true
    }

    private fun dispatchDisconnect(reason: String) {
        if (shutdown()) dispatch(EVENT_DISCONNECT, arrayOf(reason))
    }

    private fun fail(reason: String) {
        if (!shutdown()) return

        dispatch(EVENT_CONNECT_ERROR, arrayOf(reason))
        onConnectFailed(reason)
    }


    private companion object {
        const val TAG = "LanSocket"
        const val CONNECT_TIMEOUT_MS = 5000
    }
}
