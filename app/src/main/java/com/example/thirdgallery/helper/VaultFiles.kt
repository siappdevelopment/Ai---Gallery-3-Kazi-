package com.example.thirdgallery.helper

import android.content.Context
import java.io.File

/**
 * The vault's private folder. It is inside the app's no-backup storage, which no other app can read, is
 * never indexed by MediaStore (the files have no extension and a .nomedia marker sits beside them) and is
 * left out of cloud backups, which could not bring the files back anyway.
 */
object VaultFiles {

    private const val DIR = "vault"

    fun dir(context: Context): File {
        val dir = File(context.applicationContext.noBackupFilesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        val marker = File(dir, ".nomedia")
        if (!marker.exists()) {
            try {
                marker.createNewFile()
            } catch (e: java.io.IOException) {
                // Best effort: MediaStore does not scan this private folder anyway.
            }
        }
        return dir
    }

    fun file(context: Context, fileName: String): File = File(dir(context), fileName)
}
