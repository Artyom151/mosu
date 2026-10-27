package com.mosu.lan

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire format of LAN multiplayer.
 *
 * Every frame is a single line of UTF-8 JSON terminated by `\n`: `{"e": "<event>", "a": [<args>]}`. Event names and
 * argument shapes are the same as the online (socket.io) multiplayer server, so the existing room client can be reused
 * as is. The only LAN-specific frames are:
 *
 * - `hello` (client → server): the first frame, with `{uid, username, password, version}`.
 * - `connect_error` (server → client): the connection was refused, with a human-readable reason.
 * - `disconnect` (server → client): the room was closed by the server, with a socket.io-like reason.
 * - `lanInfo` (server → client): sent after `initialConnection`, with `{filePort}`.
 * - `ping` (both ways): keep-alive, ignored.
 */
object LanProtocol {

    const val VERSION = 1

    /**
     * The TCP port rooms are hosted on. If it's taken, an ephemeral port is used and announced through discovery.
     */
    const val ROOM_PORT = 47331

    /**
     * The UDP port room announcements are broadcast to.
     */
    const val DISCOVERY_PORT = 47332

    /**
     * The TCP port beatmaps are shared on (see [LanFileServer]), announced to players with a `lanInfo` frame.
     */
    const val FILE_PORT = 47333

    const val DISCOVERY_APP = "mosu-lan"

    /**
     * Frames bigger than this are rejected to protect against malformed or malicious peers.
     */
    const val MAX_FRAME_BYTES = 512 * 1024

    /**
     * Interval between keep-alive frames. A peer that sends nothing for [TIMEOUT_MS] is considered gone.
     */
    const val PING_INTERVAL_MS = 4000L
    const val TIMEOUT_MS = 15000


    fun encode(event: String, args: Array<out Any?>): ByteArray {
        val array = JSONArray()

        for (arg in args) {
            array.put(arg ?: JSONObject.NULL)
        }

        return (JSONObject().put("e", event).put("a", array).toString() + "\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(frame: String): Pair<String, Array<Any?>> {
        val json = JSONObject(frame)
        val array = json.optJSONArray("a") ?: JSONArray()

        return json.getString("e") to Array(array.length()) { i -> array.get(i).takeUnless { it == JSONObject.NULL } }
    }

    /**
     * Reads a frame from [input] (which should be buffered), or returns null at the end of the stream.
     */
    fun readFrame(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()

        while (true) {
            val byte = input.read()

            if (byte < 0) {
                return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            }

            if (byte == '\n'.code) {
                return buffer.toString("UTF-8")
            }

            buffer.write(byte)

            if (buffer.size() > MAX_FRAME_BYTES) {
                throw IOException("Frame too large")
            }
        }
    }
}
