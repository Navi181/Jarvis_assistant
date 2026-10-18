package com.jarvis.assistant.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lets you attach a video, photo, audio clip or PDF to the chat and ask about it.
 * The file goes to Gemini's file store (free with your Gemini key), is processed there, and is reused for follow-up
 * questions so it is only uploaded once. Blocking: call from a background thread.
 */
object MediaAsk {
    class Attachment(val uri: Uri, val name: String, val mime: String, val size: Long) {
        val kind: String
            get() = when {
                mime.startsWith("video") -> "video"
                mime.startsWith("image") -> "photo"
                mime.startsWith("audio") -> "audio clip"
                else -> "file"
            }
    }

    private const val MAX_BYTES = 500L * 1024 * 1024
    private const val BASE = "https://generativelanguage.googleapis.com"

    private class Uploaded(val key: String, val name: String, val uri: String, val mime: String, val at: Long)
    @Volatile private var uploaded: Uploaded? = null

    fun describe(ctx: Context, uri: Uri): Attachment {
        var name = "attachment"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n) ?: name
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (_: Exception) {
        }
        var mime = ctx.contentResolver.getType(uri).orEmpty()
        if (mime.isBlank() || mime == "application/octet-stream") {
            mime = when (name.substringAfterLast('.', "").lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"
                "mkv" -> "video/x-matroska"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "pdf" -> "application/pdf"
                else -> "video/mp4"
            }
        }
        return Attachment(uri, name, mime, size)
    }

    fun defaultQuestion(a: Attachment) = when {
        a.mime.startsWith("video") -> "Describe this video: what happens, who or what is in it, and anything worth noting."
        a.mime.startsWith("image") -> "Describe this photo and anything worth noting in it."
        a.mime.startsWith("audio") -> "Tell me what is in this audio: speech, music, sounds, and a short summary."
        else -> "Summarise this document."
    }

    /** Uploads (once) and asks. Throws [LlmFailure] or [IOException]. */
    fun analyze(ctx: Context, a: Attachment, question: String, detailed: Boolean): String {
        val key = Prefs.keyFor("gemini")
        if (key.isBlank()) throw LlmFailure(LlmFailure.Kind.NO_KEY, "gemini")
        val up = ensureUploaded(ctx, a, key)
        val system = "You are Jarvis, a calm, witty voice assistant. The user attached a ${a.kind} and is asking about it. " +
            "Answer only from what is really in it; say so if something cannot be told. Plain spoken English, no markdown, no bullet symbols. " +
            (if (detailed) "Give a thorough answer of about 250 to 350 words." else "Keep it to about 4 to 6 short sentences unless the question needs more.")
        val parts = JSONArray()
            .put(JSONObject().put("file_data", JSONObject().put("mime_type", up.mime).put("file_uri", up.uri)))
            .put(JSONObject().put("text", question))
        return try {
            Llm.askWithMedia(system, parts)
        } catch (e: LlmFailure) {
            // the stored file may have expired: upload again once
            if (e.kind == LlmFailure.Kind.OTHER || e.kind == LlmFailure.Kind.BAD_KEY) {
                uploaded = null
                val again = ensureUploaded(ctx, a, key)
                val p2 = JSONArray()
                    .put(JSONObject().put("file_data", JSONObject().put("mime_type", again.mime).put("file_uri", again.uri)))
                    .put(JSONObject().put("text", question))
                Llm.askWithMedia(system, p2)
            } else throw e
        }
    }

    fun forget() {
        uploaded = null
    }

    // ------------------------------------------------------------------ Gemini file store

    private fun ensureUploaded(ctx: Context, a: Attachment, key: String): Uploaded {
        uploaded?.let {
            if (it.key == a.uri.toString() + key && System.currentTimeMillis() - it.at < 40L * 3600_000L) return it
        }
        if (a.size > MAX_BYTES) throw IOException("TOO_BIG")
        val tmp = File(ctx.cacheDir, "attach_" + System.nanoTime())
        try {
            ctx.contentResolver.openInputStream(a.uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                ?: throw IOException("Cannot read that file")
            val len = tmp.length()
            if (len <= 0L) throw IOException("That file is empty")
            if (len > MAX_BYTES) throw IOException("TOO_BIG")

            // 1) start a resumable upload
            val start = URL("$BASE/upload/v1beta/files").openConnection() as HttpURLConnection
            val sessionUrl: String
            try {
                start.requestMethod = "POST"
                start.connectTimeout = 15_000
                start.readTimeout = 60_000
                start.doOutput = true
                start.setRequestProperty("x-goog-api-key", key)
                start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
                start.setRequestProperty("X-Goog-Upload-Command", "start")
                start.setRequestProperty("X-Goog-Upload-Header-Content-Length", len.toString())
                start.setRequestProperty("X-Goog-Upload-Header-Content-Type", a.mime)
                start.setRequestProperty("Content-Type", "application/json")
                start.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", a.name.take(60))).toString().toByteArray()) }
                val code = start.responseCode
                if (code !in 200..299) {
                    val err = start.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException("HTTP $code ${err.take(150)}")
                }
                sessionUrl = start.getHeaderField("X-Goog-Upload-URL") ?: throw IOException("No upload address")
            } finally {
                start.disconnect()
            }

            // 2) send the bytes
            val send = URL(sessionUrl).openConnection() as HttpURLConnection
            val body: String
            try {
                send.requestMethod = "POST"
                send.connectTimeout = 15_000
                send.readTimeout = 300_000
                send.doOutput = true
                send.setFixedLengthStreamingMode(len)
                send.setRequestProperty("X-Goog-Upload-Offset", "0")
                send.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
                tmp.inputStream().use { i -> send.outputStream.use { o -> i.copyTo(o, 64 * 1024) } }
                val code = send.responseCode
                val text = (if (code in 200..299) send.inputStream else send.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IOException("HTTP $code ${text.take(150)}")
                body = text
            } finally {
                send.disconnect()
            }
            val file = JSONObject(body).getJSONObject("file")
            val name = file.getString("name")
            var state = file.optString("state", "ACTIVE")
            val fileUri = file.getString("uri")

            // 3) videos need a little time to be processed
            var waited = 0
            while (state == "PROCESSING" && waited < 150) {
                Thread.sleep(2000)
                waited += 2
                state = JSONObject(Net.get("$BASE/v1beta/$name", mapOf("x-goog-api-key" to key))).optString("state", "ACTIVE")
            }
            if (state == "FAILED") throw IOException("Gemini could not read that file")
            if (state == "PROCESSING") throw IOException("Gemini is still processing it, try again in a minute")
            val up = Uploaded(a.uri.toString() + key, name, fileUri, file.optString("mimeType", a.mime), System.currentTimeMillis())
            uploaded = up
            return up
        } finally {
            tmp.delete()
        }
    }

    /** Plain words for what went wrong. */
    fun friendly(e: Throwable): String = when {
        e is LlmFailure && e.kind == LlmFailure.Kind.NO_KEY -> "To look at videos and photos I need a free Gemini key. Add one in setup."
        e is LlmFailure -> Brain.friendly(e)
        e.message.orEmpty().contains("TOO_BIG") -> "That file is too big. I can handle up to about 500 megabytes."
        e.message.orEmpty().contains("HTTP 429") -> "Gemini's free quota is used up for now. Try again in a few minutes."
        e.message.orEmpty().contains("HTTP 401") || e.message.orEmpty().contains("HTTP 403") -> "Gemini didn't accept my key for file uploads. Please check it in setup."
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> "The upload didn't finish. Check your internet and try again."
        else -> "I couldn't analyse that: " + e.message.orEmpty().take(110)
    }
}
