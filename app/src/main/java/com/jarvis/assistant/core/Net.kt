package com.jarvis.assistant.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP helper (no extra libraries). Call from a background thread only. */
object Net {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String = call("GET", url, headers, null)

    fun post(url: String, headers: Map<String, String>, body: String): String = call("POST", url, headers, body)

    /** Streams a file (follows GitHub's redirect to the storage host). */
    fun download(url: String, out: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 90_000
            c.setRequestProperty("User-Agent", "Jarvis-Android")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } finally {
            c.disconnect()
        }
    }

    private fun call(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", "Jarvis-Android")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(150)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
