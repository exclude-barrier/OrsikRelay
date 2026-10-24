package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The line icons of design v2 (24 viewport units, round caps and joins).
 * The design draws them as strokes; [androidx.compose.material3.Icon] tints
 * strokes and fills alike, so they behave like any Material icon.
 */
object OrsikIcons {
    val Home by lazy { stroke("Home", 2f, "M4 11 12 4l8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z") }
    val Agents by lazy {
        stroke("Agents", 1.8f, rect(5f, 8f, 14f, 11f, 3f), "M12 4v4M9.5 13h.01M14.5 13h.01M2 13v2M22 13v2")
    }
    val Server by lazy {
        stroke("Server", 1.8f, rect(3f, 4f, 18f, 7f, 2f), rect(3f, 13f, 18f, 7f, 2f), "M7 7.5h.01M7 16.5h.01")
    }
    val Activity by lazy { stroke("Activity", 1.8f, "M3 12h4l3-7 4 14 3-7h4") }
    val Relay by lazy {
        stroke("Relay", 2.2f, "M11 7.5a6.5 6.5 0 0 1 0 9", "M15 4a11 11 0 0 1 0 16", fills = listOf(circle(6f, 12f, 2.5f)))
    }
    val ChevronDown by lazy { stroke("ChevronDown", 2.2f, "m7 10 5 5 5-5") }
    val ChevronRight by lazy { stroke("ChevronRight", 2f, "m9 6 6 6-6 6") }
    val Shield by lazy { stroke("Shield", 2.2f, "M12 3 4 6v6c0 4.5 3.4 8 8 9 4.6-1 8-4.5 8-9V6z", "M12 8v5M12 16.5v.01") }
    val Check by lazy { stroke("Check", 2.6f, "m5 12 5 5 9-10") }
    val Plus by lazy { stroke("Plus", 2f, "M12 5v14M5 12h14") }
    val ArrowUp by lazy { stroke("ArrowUp", 2.4f, "M12 19V5M6 11l6-6 6 6") }
    val ArrowBack by lazy { stroke("ArrowBack", 2f, "M19 12H5M11 6l-6 6 6 6", autoMirror = true) }
    val Chat by lazy { stroke("Chat", 2.2f, "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z") }
    val Terminal by lazy { stroke("Terminal", 1.9f, "m5 8 4 4-4 4M12 17h7") }
    val TerminalBox by lazy { stroke("TerminalBox", 2f, rect(3f, 4f, 18f, 16f, 3f), "m7 9 3 3-3 3M13 15h4") }
    val File by lazy { stroke("File", 1.9f, "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5") }
    val FileText by lazy { stroke("FileText", 1.9f, "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M9 13h6M9 17h4") }
    val FileBinary by lazy { stroke("FileBinary", 1.9f, "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M9 13v4M12 13v4M15 13v4") }
    val Image by lazy { stroke("Image", 1.9f, "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M8.5 7a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z", "m20 15-4.5-4.5L6 20") }
    val Share by lazy { stroke("Share", 1.9f, "M18 3a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M6 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M18 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "m8.6 13.5 6.8 4M15.4 6.5l-6.8 4") }
    val Link by lazy { stroke("Link", 1.9f, "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7") }
    val Pencil by lazy { stroke("Pencil", 1.9f, "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z") }
    val Grid by lazy { stroke("Grid", 1.9f, "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z") }
    val ListView by lazy { stroke("ListView", 1.9f, "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01") }
    val Sort by lazy { stroke("Sort", 1.9f, "m3 16 4 4 4-4M7 20V4", "m21 8-4-4-4 4M17 4v16") }
    val Keyboard by lazy { stroke("Keyboard", 1.9f, "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M6 10h.01M10 10h.01M14 10h.01M18 10h.01M8 14h8") }
    val Close by lazy { stroke("Close", 1.9f, "M18 6 6 18M6 6l12 12") }
    val Download by lazy { stroke("Download", 1.9f, "M12 4v11", "m7 11 5 5 5-5", "M5 20h14") }
    val Folder by lazy { stroke("Folder", 1.9f, "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val Container by lazy { stroke("Container", 1.9f, "m12 3 8 4.5v9L12 21l-8-4.5v-9z", "m4 7.5 8 4.5 8-4.5M12 12v9") }
    val Code by lazy { stroke("Code", 1.9f, "m8 8-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14") }
    val Send by lazy { stroke("Send", 2f, "M4 12 20 4l-4 16-4-7z", "m12 13 8-9") }
    val Calendar by lazy { stroke("Calendar", 2f, rect(3f, 5f, 18f, 16f, 2f), "M3 10h18M8 3v4M16 3v4") }
    val Search by lazy { stroke("Search", 1.9f, circle(11f, 11f, 6.5f), "m20 20-4.2-4.2") }
    val Pin by lazy { stroke("Pin", 1.8f, "M9 4h6l-1 6 3 3H7l3-3zM12 13v7") }
    val Wrench by lazy {
        stroke("Wrench", 2f, "M14.7 6.3a4 4 0 0 0-5.4 5.4L4 17l3 3 5.3-5.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z")
    }
    val Copy by lazy {
        stroke("Copy", 2f, rect(8f, 8f, 12f, 12f, 2f), "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2")
    }
    val Globe by lazy { stroke("Globe", 1.9f, circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18") }
    val Palette by lazy {
        stroke(
            "Palette", 1.9f,
            "M12 3a9 9 0 1 0 0 18c1.2 0 1.6-1 1-2-.7-1.2 0-2.5 1.4-2.5H17a4 4 0 0 0 4-4C21 7 17 3 12 3z",
            circle(7.5f, 11f, 1f), circle(10f, 7.5f, 1f), circle(14.5f, 7.5f, 1f)
        )
    }
    val Wallpaper by lazy { stroke("Wallpaper", 1.9f, rect(3f, 4f, 18f, 16f, 2f), "m3 16 5-5 4 4 3-3 6 6") }
    val TextSize by lazy { stroke("TextSize", 1.9f, "M4 7V5h10v2M9 5v14M14 13v-2h6v2M17 11v8") }
    val Lock by lazy { stroke("Lock", 1.9f, rect(5f, 11f, 14f, 10f, 2f), "M8 11V8a4 4 0 0 1 8 0v3") }
    val Eye by lazy { stroke("Eye", 1.9f, "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", circle(12f, 12f, 3f)) }
    val Scan by lazy {
        stroke("Scan", 2.2f, "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M8 12h8")
    }
    val Archive by lazy { stroke("Archive", 1.9f, rect(3f, 4f, 18f, 5f, 1.5f), "M5 9v9a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9M10 13h4") }
    val Trash by lazy { stroke("Trash", 1.9f, "M4 7h16M10 3h4M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M10 11v6M14 11v6") }
    val Key by lazy { stroke("Key", 2f, circle(8f, 15f, 4f), "m11 12 9-9M17 6l3 3M15 8l2 2") }
    val MoreVert by lazy {
        stroke("MoreVert", 1.9f, fills = listOf(circle(12f, 5f, 1.4f), circle(12f, 12f, 1.4f), circle(12f, 19f, 1.4f)))
    }
}

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}" +
        "h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"

private fun stroke(
    name: String,
    width: Float,
    vararg paths: String,
    fills: List<String> = emptyList(),
    autoMirror: Boolean = false
): ImageVector {
    val builder = ImageVector.Builder(
        name = "Orsik.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    )
    paths.forEach { data ->
        builder.addPath(
            pathData = addPathNodes(data),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }
    fills.forEach { data ->
        builder.addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black))
    }
    return builder.build()
}
