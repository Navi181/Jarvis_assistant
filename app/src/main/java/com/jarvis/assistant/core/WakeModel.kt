package com.jarvis.assistant.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** The small offline speech model used to hear "Jarvis" without any account or key. Downloaded once (about 40 MB). */
object WakeModel {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val DIR = "vosk-model"

    @Volatile
    var lastError: String = ""

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    fun ready(ctx: Context): Boolean = File(dir(ctx), "am/final.mdl").exists()

    /** Blocking. Call from a background thread. [onProgress] gets 0..100 while downloading. Returns true on success. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): Boolean {
        lastError = ""
        val zip = File(ctx.cacheDir, "wake-model.zip")
        val tmp = File(ctx.filesDir, "$DIR-tmp")
        try {
            val c = URL(URL_ZIP).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = true
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "Jarvis-Android")
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 90 / total).toInt()
                                if (pct != last) {
                                    last = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }

            // unpack, dropping the single top-level folder inside the zip
            tmp.deleteRecursively()
            tmp.mkdirs()
            val root = tmp.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(tmp, rel)
                    if (!f.canonicalPath.startsWith(root)) continue // never write outside the folder
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
            onProgress(97)
            if (!File(tmp, "am/final.mdl").exists()) throw IOException("The downloaded model looks incomplete")
            val dest = dir(ctx)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the model")
            onProgress(100)
            return true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            tmp.deleteRecursively()
            return false
        } finally {
            zip.delete()
        }
    }
}
