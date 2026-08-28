package com.chrispixel.chrisai.ui.components

import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

/**
 * Renders Markdown (including code blocks) into a selectable TextView.
 * Uses Markwon and updates incrementally while a reply streams in.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    fontSize: TextUnit = 16.sp,
    linkColor: Color = color
) {
    val context = LocalContext.current
    val markwon = remember { Markwon.create(context) }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                textSize = fontSize.value
                setLinkTextColor(linkColor.toArgb())
                setTextColor(color.toArgb())
                includeFontPadding = false
            }
        },
        update = { view ->
            view.setTextColor(color.toArgb())
            view.setLinkTextColor(linkColor.toArgb())
            markwon.setMarkdown(view, text)
        },
        modifier = modifier
    )
}