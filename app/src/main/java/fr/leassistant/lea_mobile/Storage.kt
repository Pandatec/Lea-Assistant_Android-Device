package fr.leassistant.lea_mobile

import androidx.appcompat.app.AppCompatActivity
import java.io.File

class Storage(activity: AppCompatActivity) {
    private val activity = activity

    private fun getFile(filename: String): File {
        return File(activity.getExternalFilesDir(null), filename)
    }

    fun write(filename: String, content: ByteArray) {
        getFile(filename).writeBytes(content)
    }
    fun read(filename: String): ByteArray {
        try {
            return getFile(filename).readBytes()
        } catch (e: java.io.FileNotFoundException) {
            return ByteArray(0)
        }
    }
    fun erase(filename: String) {
        try {
            getFile(filename).delete()
        } catch (e: java.io.FileNotFoundException) {
        }
    }
}