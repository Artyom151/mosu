package com.osudroid.ui.skinning

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkinTextureUtilsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun png(name: String, width: Int, height: Int): File {
        val header = ByteBuffer.allocate(33)
            .put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
            .putInt(13)
            .put("IHDR".toByteArray())
            .putInt(width)
            .putInt(height)
            .array()

        return folder.newFile(name).apply { writeBytes(header) }
    }

    @Test
    fun `1x1 placeholder is not usable`() {
        assertFalse(SkinTextureUtils.isUsableTexture(png("spinner-top.png", 1, 1)))
    }

    @Test
    fun `regular texture is usable`() {
        assertTrue(SkinTextureUtils.isUsableTexture(png("spinner-circle.png", 300, 300)))
        assertTrue(SkinTextureUtils.isUsableTexture(png("spinner-metre.png", 1023, 692)))
    }

    @Test
    fun `missing and empty files are not usable`() {
        assertFalse(SkinTextureUtils.isUsableTexture(File(folder.root, "missing.png")))
        assertFalse(SkinTextureUtils.isUsableTexture(folder.newFile("empty.png")))
    }
}
