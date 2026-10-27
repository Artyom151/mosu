package com.osudroid.ui.skinning

import java.io.File

/**
 * Utilities to inspect skin textures without decoding them.
 */
object SkinTextureUtils {

    /**
     * Skins hide elements by shipping 1x1 transparent images. A texture is considered a placeholder when its width
     * or height is at most this size.
     */
    private const val PLACEHOLDER_SIZE = 1

    /**
     * Whether [file] is a real, visible-sized PNG texture (it exists, is not empty and is larger than a 1x1
     * placeholder). Non-PNG files are assumed to be usable since their size cannot be read cheaply.
     */
    @JvmStatic
    fun isUsableTexture(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) {
            return false
        }

        val header = ByteArray(24)

        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            return false
        }

        // PNG signature followed by the IHDR chunk, which stores width and height as big-endian integers.
        if (read < header.size || header[1] != 'P'.code.toByte() || header[2] != 'N'.code.toByte() || header[3] != 'G'.code.toByte()) {
            return true
        }

        return readInt(header, 16) > PLACEHOLDER_SIZE && readInt(header, 20) > PLACEHOLDER_SIZE
    }

    private fun readInt(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF shl 24) or
        (bytes[offset + 1].toInt() and 0xFF shl 16) or
        (bytes[offset + 2].toInt() and 0xFF shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}
