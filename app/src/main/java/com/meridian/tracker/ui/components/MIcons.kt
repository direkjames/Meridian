package com.meridian.tracker.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Meridian's own line icons (24dp grid, 2dp strokes). Tinted by Icon(tint = ...). */
object MIcons {

    val Pause: ImageVector by lazy {
        fill("pause", "M7,5h3.6v14H7z", "M13.4,5H17v14h-3.6z")
    }
    val Play: ImageVector by lazy {
        fill("play", "M8,5.5v13a1,1 0 0,0 1.5,0.86l10.2,-6.5a1,1 0 0,0 0,-1.72L9.5,4.64A1,1 0 0,0 8,5.5z")
    }
    val Lock: ImageVector by lazy {
        stroke(
            "lock",
            "M7,11h10a2,2 0 0,1 2,2v6a2,2 0 0,1 -2,2H7a2,2 0 0,1 -2,-2v-6a2,2 0 0,1 2,-2z",
            "M8,11V8a4,4 0 0,1 8,0v3",
        )
    }
    val Unlock: ImageVector by lazy {
        stroke(
            "unlock",
            "M7,11h10a2,2 0 0,1 2,2v6a2,2 0 0,1 -2,2H7a2,2 0 0,1 -2,-2v-6a2,2 0 0,1 2,-2z",
            "M8,11V8a4,4 0 0,1 7.7,-1.5",
        )
    }
    val List: ImageVector by lazy { stroke("list", "M4,7h16", "M4,12h16", "M4,17h10") }
    val Back: ImageVector by lazy { stroke("back", "M15,5l-7,7 7,7") }
    val Close: ImageVector by lazy { stroke("close", "M6,6l12,12", "M18,6L6,18") }
    val Check: ImageVector by lazy { stroke("check", "M5,12.5l4.5,4.5L19,7.5") }
    val Trash: ImageVector by lazy {
        stroke(
            "trash",
            "M4,7h16",
            "M10,11v6",
            "M14,11v6",
            "M6,7l1,12a2,2 0 0,0 2,2h6a2,2 0 0,0 2,-2l1,-12",
            "M9,7V4h6v3",
        )
    }
    val Locate: ImageVector by lazy {
        stroke(
            "locate",
            "M12,5a7,7 0 1,1 0,14a7,7 0 1,1 0,-14z",
            "M12,2v3", "M12,19v3", "M2,12h3", "M19,12h3",
        )
    }
    val Flag: ImageVector by lazy { stroke("flag", "M5,21V4", "M5,4h12l-2,4 2,4H5") }
    val Record: ImageVector by lazy {
        stroke("record", "M12,3.5a8.5,8.5 0 1,1 0,17a8.5,8.5 0 1,1 0,-17z", "M12,9a3,3 0 1,1 0,6a3,3 0 1,1 0,-6z")
    }
    val Pulse: ImageVector by lazy { stroke("pulse", "M3,12h4l2.5,-6 5,12 2.5,-6h4") }
    val Chart: ImageVector by lazy { stroke("chart", "M5,20V13", "M12,20V5", "M19,20v-9") }
    val Trophy: ImageVector by lazy {
        stroke(
            "trophy",
            "M8,21h8", "M12,17v4",
            "M7,4h10v5a5,5 0 0,1 -10,0V4z",
            "M7,6H4.5a3,3 0 0,0 3,4.5", "M17,6h2.5a3,3 0 0,1 -3,4.5",
        )
    }
    val ChevronLeft: ImageVector by lazy { stroke("chevl", "M14.5,6l-6,6 6,6") }
    val ChevronRight: ImageVector by lazy { stroke("chevr", "M9.5,6l6,6 -6,6") }
    val Download: ImageVector by lazy {
        stroke("download", "M12,4v11", "M7,10.5l5,5 5,-5", "M5,20h14")
    }
    val Map: ImageVector by lazy {
        stroke("map", "M9,4L3,6.5v13.5l6,-2.5 6,2.5 6,-2.5V4l-6,2.5z", "M9,4v13.5", "M15,6.5V20")
    }
    val Share: ImageVector by lazy {
        stroke("share", "M12,3v12", "M8,7l4,-4 4,4", "M5,12v7a2,2 0 0,0 2,2h10a2,2 0 0,0 2,-2v-7")
    }
    val Settings: ImageVector by lazy {
        stroke(
            "settings",
            "M4,7h10", "M18,7h2", "M4,17h2", "M10,17h10",
            "M16,5a2,2 0 1,1 0,4a2,2 0 1,1 0,-4z",
            "M8,15a2,2 0 1,1 0,4a2,2 0 1,1 0,-4z",
        )
    }
    val Calendar: ImageVector by lazy {
        stroke(
            "calendar",
            "M5,6h14a1,1 0 0,1 1,1v12a1,1 0 0,1 -1,1H5a1,1 0 0,1 -1,-1V7a1,1 0 0,1 1,-1z",
            "M4,10h16", "M8,4v4", "M16,4v4",
        )
    }
    val Heart: ImageVector by lazy {
        stroke("heart", HEART)
    }
    val HeartFilled: ImageVector by lazy { fill("heartFilled", HEART) }
    val Watch: ImageVector by lazy {
        stroke(
            "watch",
            "M8,7h8a2,2 0 0,1 2,2v6a2,2 0 0,1 -2,2H8a2,2 0 0,1 -2,-2V9a2,2 0 0,1 2,-2z",
            "M9,7l0.8,-4h4.4l0.8,4", "M9,17l0.8,4h4.4l0.8,-4", "M12,10v2l1.5,1",
        )
    }
    val Steps: ImageVector by lazy {
        stroke(
            "steps",
            "M8,3.5a2.3,3.6 0 1,1 0,7.2a2.3,3.6 0 1,1 0,-7.2z",
            "M8,13a1.6,1.6 0 1,1 0,3.2a1.6,1.6 0 1,1 0,-3.2z",
            "M16,7.5a2.3,3.6 0 1,1 0,7.2a2.3,3.6 0 1,1 0,-7.2z",
            "M16,17a1.6,1.6 0 1,1 0,3.2a1.6,1.6 0 1,1 0,-3.2z",
        )
    }
    val Bluetooth: ImageVector by lazy { stroke("bluetooth", "M7,7l10,10 -5,4V3l5,4L7,17") }
    val Sync: ImageVector by lazy {
        stroke("sync", "M4.5,12a7.5,7.5 0 0,1 13,-5", "M17.5,3v4h-4", "M19.5,12a7.5,7.5 0 0,1 -13,5", "M6.5,21v-4h4")
    }
    val Edit: ImageVector by lazy { stroke("edit", "M4,20h4L19,9l-4,-4L4,16z", "M13.5,6.5l4,4") }

    private const val HEART =
        "M12,20C12,20 4.5,15.5 4.5,10A4.25,4.25 0 0,1 12,7.3A4.25,4.25 0 0,1 19.5,10C19.5,15.5 12,20 12,20z"

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun fill(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White)) }
        }.build()
}
