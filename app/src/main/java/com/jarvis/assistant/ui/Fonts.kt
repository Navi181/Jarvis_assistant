package com.jarvis.assistant.ui

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** The look of the reference Jarvis: a bold serif for titles, clean sans for text, mono for small labels. */
@OptIn(ExperimentalTextApi::class)
object Fonts {
    var title: FontFamily = FontFamily.Serif
        private set
    var body: FontFamily = FontFamily.Default
        private set
    var mono: FontFamily = FontFamily.Monospace
        private set

    private fun has(ctx: Context, path: String) = try {
        ctx.assets.open(path).close(); true
    } catch (e: Exception) {
        false
    }

    private fun family(ctx: Context, path: String, italic: Boolean = false): FontFamily? {
        if (!has(ctx, path)) return null
        return try {
            FontFamily(
                listOf(400, 500, 600, 700).map { w ->
                    Font(
                        ctx.assets, path, FontWeight(w),
                        if (italic) FontStyle.Italic else FontStyle.Normal,
                        FontVariation.Settings(FontVariation.weight(w))
                    )
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun load(ctx: Context) {
        family(ctx, "fonts/fraunces.ttf")?.let { title = it }
        family(ctx, "fonts/inter.ttf")?.let { body = it }
        family(ctx, "fonts/jetbrainsmono.ttf")?.let { mono = it }
    }
}
