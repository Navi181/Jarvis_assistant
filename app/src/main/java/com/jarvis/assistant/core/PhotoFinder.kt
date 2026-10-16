package com.jarvis.assistant.core

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds and opens a photo from a vague request. Everything about the pictures is worked out ON THE PHONE:
 * dates and GPS from the gallery, scenes from Google's on-device image labeller, people COUNTED by on-device
 * face detection (nobody is identified, and no picture leaves the phone).
 */
object PhotoFinder {
    private const val MAX_ROWS = 400
    private const val ANALYSE_BUDGET = 120
    private const val NEAR_KM = 60.0

    private class Row(val id: Long, val takenMs: Long)
    private class Info(val labels: List<String>, val faces: Int)

    fun hasAccess(ctx: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationAccess(ctx: Context) =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun find(ctx: Context, q: PhotoQuery): Outcome = withContext(Dispatchers.IO) {
        if (!hasAccess(ctx)) {
            return@withContext Outcome("I need permission to see your photos. Open Jarvis, go to Setup and tap Grant permissions.")
        }
        if (q.place.isNotBlank() && !hasLocationAccess(ctx)) {
            return@withContext Outcome("To search photos by place I need photo location access. Please allow it in Setup.")
        }

        var target: DoubleArray? = null
        if (q.place.isNotBlank()) {
            target = geocode(q.place) ?: return@withContext Outcome("I couldn't find a place called ${q.place}.")
        }

        val rows = candidates(ctx, q)
        if (rows.isEmpty()) return@withContext Outcome("I couldn't find any photos" + (if (q.hasDate) " from then." else "."))

        val index = Index.load(ctx)
        var budget = ANALYSE_BUDGET
        var checked = 0
        var hit: Row? = null
        for (r in rows) {
            checked++
            val uri = uriOf(r.id)
            if (q.hasFilters) {
                if (target != null) {
                    val ll = latLon(ctx, uri) ?: continue
                    if (km(ll[0], ll[1], target[0], target[1]) > NEAR_KM) continue
                }
                if (q.scenes.isNotEmpty() || q.minPeople > 0) {
                    var info = index.get(r.id)
                    if (info == null) {
                        if (budget <= 0) continue
                        budget--
                        info = analyse(ctx, uri) ?: continue
                        index.put(r.id, info)
                    }
                    if (q.scenes.isNotEmpty() && q.scenes.none { s -> info.labels.any { it.contains(s) } }) continue
                    if (info.faces < q.minPeople) continue
                }
            }
            hit = r
            break
        }
        index.save(ctx)

        val found = hit ?: return@withContext Outcome(
            "I didn't find a matching photo in your $checked most recent ones" +
                (if (budget <= 0) ". I only look at a few at a time, so try adding a date to narrow it." else ".")
        )
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(found.id), "image/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(view)
        } catch (e: Exception) {
            return@withContext Outcome("I found it but couldn't open the gallery.")
        }
        val whenText = if (found.takenMs > 0) SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(found.takenMs)) else ""
        Outcome(if (whenText.isNotEmpty()) "Here's a photo from $whenText." else "Here's your photo.")
    }

    // ------------------------------------------------------------------ gallery

    private fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun candidates(ctx: Context, q: PhotoQuery): List<Row> {
        val taken = MediaStore.Images.Media.DATE_TAKEN
        val bucket = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        val sel = ArrayList<String>()
        val args = ArrayList<String>()
        if (q.hasDate) {
            sel.add("$taken >= ? AND $taken < ?")
            args.add(q.fromMs.toString())
            args.add((if (q.toMs == Long.MAX_VALUE) System.currentTimeMillis() + 86_400_000L else q.toMs).toString())
        }
        sel.add(if (q.screenshots) "$bucket LIKE '%creenshot%'" else "($bucket IS NULL OR $bucket NOT LIKE '%creenshot%')")
        val b = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel.joinToString(" AND "))
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(taken))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ROWS)
        }
        val out = ArrayList<Row>()
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, taken), b, null
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_ROWS) out.add(Row(c.getLong(0), c.getLong(1)))
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    private fun latLon(ctx: Context, uri: Uri): DoubleArray? = try {
        val u = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
        ctx.contentResolver.openInputStream(u)?.use { s ->
            val a = FloatArray(2)
            if (ExifInterface(s).getLatLong(a)) doubleArrayOf(a[0].toDouble(), a[1].toDouble()) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun geocode(place: String): DoubleArray? = try {
        val r = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(place, "UTF-8")))
            .optJSONArray("results")?.optJSONObject(0)
        if (r == null) null else doubleArrayOf(r.getDouble("latitude"), r.getDouble("longitude"))
    } catch (e: Exception) {
        null
    }

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p = Math.PI / 180
        val a = sin((lat2 - lat1) * p / 2).pow(2) + cos(lat1 * p) * cos(lat2 * p) * sin((lon2 - lon1) * p / 2).pow(2)
        return 12742 * asin(sqrt(a))
    }

    // ------------------------------------------------------------------ on-device analysis

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        )
    }

    private fun analyse(ctx: Context, uri: Uri): Info? = try {
        val bmp = thumb(ctx, uri) ?: return null
        val img = InputImage.fromBitmap(bmp, 0)
        val labels = Tasks.await(labeler.process(img)).map { it.text.lowercase() }
        val faces = Tasks.await(faceDetector.process(img)).size
        Info(labels, faces)
    } catch (e: Exception) {
        null
    }

    private fun thumb(ctx: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return try { ctx.contentResolver.loadThumbnail(uri, Size(640, 640), null) } catch (e: Exception) { null }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (bounds.outWidth / (s * 2) >= 640 && bounds.outHeight / (s * 2) >= 640) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Remembers what was seen in each photo so repeat searches are instant. Stays on the phone. */
    private class Index(private val map: HashMap<Long, Info>) {
        fun get(id: Long) = map[id]
        fun put(id: Long, i: Info) { map[id] = i }

        fun save(ctx: Context) {
            try {
                val o = JSONObject()
                map.entries.toList().takeLast(3000).forEach { (id, i) ->
                    o.put(id.toString(), JSONObject().put("l", JSONArray(i.labels)).put("f", i.faces))
                }
                File(ctx.filesDir, "photo_index.json").writeText(o.toString())
            } catch (_: Exception) {
            }
        }

        companion object {
            fun load(ctx: Context): Index {
                val m = HashMap<Long, Info>()
                try {
                    val f = File(ctx.filesDir, "photo_index.json")
                    if (f.exists()) {
                        val o = JSONObject(f.readText())
                        o.keys().forEach { k ->
                            val e = o.getJSONObject(k)
                            val l = e.getJSONArray("l")
                            m[k.toLong()] = Info((0 until l.length()).map { l.getString(it) }, e.optInt("f", 0))
                        }
                    }
                } catch (_: Exception) {
                }
                return Index(m)
            }
        }
    }
}
