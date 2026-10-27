package com.mosu.skins

import java.io.File
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.helper.FileUtils

object SkinInstaller {

    /**
     * Removes characters that are illegal in FAT32 file names and path separators.
     */
    fun sanitizeName(name: String): String =
        name.replace("[\"*/:<>?\\\\|\\p{Cntrl}]".toRegex(), "_").trim().trim('.').ifEmpty { "skin" }.take(100)

    /**
     * Extracts [archive] (an .osk file) into the skins directory and registers it. The archive is deleted on success.
     *
     * @return The name of the installed skin or null if the extraction failed.
     */
    fun install(archive: File, displayName: String): String? {

        val name = sanitizeName(displayName)
        val renamed = File(archive.parentFile, "$name.osk")

        if (renamed != archive && !archive.renameTo(renamed)) {
            return null
        }

        val skinsPath = Config.getSkinTopPath()

        if (!FileUtils.extractZip(renamed.path, skinsPath)) {
            return null
        }

        Config.addSkin(name, File(skinsPath, name).path)
        return name
    }
}
