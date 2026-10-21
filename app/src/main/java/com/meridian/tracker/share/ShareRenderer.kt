package com.meridian.tracker.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.meridian.tracker.R
import com.meridian.tracker.data.ActivityMedia
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Period
import com.meridian.tracker.data.Review
import com.meridian.tracker.tracking.TrackPoint
import com.meridian.tracker.ui.Format
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

enum class ShareLayout(val label: String, val transparent: Boolean) {
    STATS("Stats", true),
    ROUTE("Route", true),
    MINIMAL("Minimal", true),
    CARD("Card", false),
}

enum class Ink(val label: String, val argb: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    YELLOW("Yellow", 0xFFFFE566.toInt()),
    BLACK("Black", 0xFF0B0C0E.toInt()),
}

data class ShareOptions(
    val layout: ShareLayout = ShareLayout.STATS,
    val ink: Ink = Ink.WHITE,
    val showRoute: Boolean = true,
    val shadow: Boolean = true,
    /** One of the activity's photos as the background, or null for a see-through sticker. */
    val photoPath: String? = null,
) {
    /** True when the saved PNG keeps a see-through background. */
    val transparent: Boolean get() = layout.transparent && photoPath == null
}

/**
 * Draws share images with the plain Android canvas at 1080 px wide, so what you see in the
 * preview is exactly the file that gets saved or posted.
 */
object ShareRenderer {

    private const val W = 1080
    private const val PAD = 72f

    private const val BG = 0xFF0B0C0E.toInt()
    private const val SURFACE = 0xFF141518.toInt()
    private const val HAIRLINE = 0xFF202227.toInt()
    private const val BAR = 0xFF3C3F45.toInt()
    private const val INK = 0xFFF4F2EA.toInt()
    private const val MUTED = 0xFF9A9A93.toInt()
    private const val ACCENT = 0xFFFFE566.toInt()

    private class Fonts(context: Context) {
        val bold: Typeface = ResourcesCompat.getFont(context, R.font.instrument_sans_bold) ?: Typeface.DEFAULT_BOLD
        val regular: Typeface = ResourcesCompat.getFont(context, R.font.instrument_sans_regular) ?: Typeface.DEFAULT
        val mono: Typeface = ResourcesCompat.getFont(context, R.font.geist_mono_regular) ?: Typeface.MONOSPACE
        val monoBold: Typeface = ResourcesCompat.getFont(context, R.font.geist_mono_bold) ?: Typeface.MONOSPACE
    }

    // ---- Activity images ---------------------------------------------------------------------

    fun render(context: Context, record: ActivityRecord, options: ShareOptions): Bitmap {
        val fonts = Fonts(context)
        val photo = options.photoPath?.let { loadPhoto(it) }
        if (options.layout == ShareLayout.CARD) return card(fonts, record, options, photo)
        val sticker = when (options.layout) {
            ShareLayout.STATS -> stats(fonts, record, options)
            ShareLayout.ROUTE -> route(fonts, record, options)
            else -> minimal(fonts, record, options)
        }
        return if (photo != null) onPhoto(sticker, photo, options) else sticker
    }

    // ---- Photo backgrounds --------------------------------------------------------------------

    private const val STORY_H = 1920

    /** The last decoded background, so changing ink or layout doesn't decode the photo again. */
    private var photoCache: Pair<String, Bitmap>? = null

    @Synchronized
    private fun loadPhoto(path: String): Bitmap? {
        photoCache?.let { (p, b) -> if (p == path && !b.isRecycled) return b }
        val bmp = ActivityMedia.decodeFile(File(path), STORY_H) ?: return null
        photoCache = path to bmp
        return bmp
    }

    /** Draws [photo] to fill [dst], cropping the overflow from the centre. */
    private fun drawCover(c: Canvas, photo: Bitmap, dst: RectF) {
        val scale = max(dst.width() / photo.width, dst.height() / photo.height)
        val srcW = dst.width() / scale
        val srcH = dst.height() / scale
        val left = (photo.width - srcW) / 2f
        val top = (photo.height - srcH) / 2f
        val src = Rect(left.toInt(), top.toInt(), (left + srcW).toInt(), (top + srcH).toInt())
        c.drawBitmap(photo, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** A story-sized image: the photo, a soft fade for legibility, and the sticker at the bottom. */
    private fun onPhoto(sticker: Bitmap, photo: Bitmap, o: ShareOptions): Bitmap {
        val bmp = Bitmap.createBitmap(W, STORY_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        drawCover(c, photo, RectF(0f, 0f, W.toFloat(), STORY_H.toFloat()))

        // Dark fade behind light ink, light fade behind black ink.
        val fade = if (o.ink == Ink.BLACK) 0x8CFFFFFF.toInt() else 0xA6000000.toInt()
        val fadeTop = STORY_H * 0.38f
        c.drawRect(
            0f, fadeTop, W.toFloat(), STORY_H.toFloat(),
            Paint().apply { shader = LinearGradient(0f, fadeTop, 0f, STORY_H.toFloat(), fade and 0x00FFFFFF, fade, Shader.TileMode.CLAMP) },
        )

        val maxH = STORY_H * 0.62f
        val k = min(1f, maxH / sticker.height)
        val w = sticker.width * k
        val h = sticker.height * k
        val bottom = STORY_H - 96f
        c.drawBitmap(
            sticker,
            null,
            RectF((W - w) / 2f, bottom - h, (W + w) / 2f, bottom),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        sticker.recycle()
        return bmp
    }

    private fun stats(f: Fonts, r: ActivityRecord, o: ShareOptions): Bitmap {
        val bmp = Bitmap.createBitmap(W, 1800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val ink = o.ink.argb
        var y = PAD

        val word = paint(f.monoBold, 36f, ink, o, spacing = 0.32f, alpha = 0.9f)
        y += 36f
        c.drawText("MERIDIAN", PAD, y, word)
        y += 70f

        val label = paint(f.mono, 34f, ink, o, spacing = 0.18f, alpha = 0.8f)
        y += 34f
        c.drawText("${r.sport.label.uppercase()} · DISTANCE", PAD, y, label)
        y += 28f

        val big = paint(f.bold, 280f, ink, o, spacing = -0.04f, tnum = true)
        val km = Format.km(r.distanceM)
        val unit = paint(f.regular, 92f, ink, o, alpha = 0.85f)
        fitHero(big, unit, km, W - 2 * PAD)
        y += -big.fontMetrics.ascent * 0.92f
        c.drawText(km, PAD, y, big)
        c.drawText(" ${Format.distUnit}", PAD + big.measureText(km), y, unit)
        y += 70f

        statRow(c, f, o, r, y)
        y += 34f + 18f + 110f

        if (o.showRoute && r.points.size >= 2) {
            y += 56f
            val box = RectF(PAD, y, W - PAD, y + 440f)
            drawRoute(c, r.points, box, routeColor(o), 14f, o.shadow)
            y = box.bottom
        }
        return crop(bmp, (y + PAD).toInt())
    }

    private fun route(f: Fonts, r: ActivityRecord, o: ShareOptions): Bitmap {
        val bmp = Bitmap.createBitmap(W, 1700, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val ink = o.ink.argb
        var y = PAD

        val word = paint(f.monoBold, 36f, ink, o, spacing = 0.32f, alpha = 0.9f)
        y += 36f
        c.drawText("MERIDIAN", PAD, y, word)
        y += 40f

        if (r.points.size >= 2) {
            val box = RectF(PAD, y, W - PAD, y + (W - 2 * PAD))
            drawRoute(c, r.points, box, routeColor(o), 16f, o.shadow)
            y = box.bottom + 40f
        }

        val big = paint(f.bold, 160f, ink, o, spacing = -0.03f, tnum = true)
        val km = Format.km(r.distanceM)
        val unit = paint(f.regular, 64f, ink, o, alpha = 0.85f)
        fitHero(big, unit, km, W - 2 * PAD)
        y += -big.fontMetrics.ascent * 0.92f
        c.drawText(km, PAD, y, big)
        c.drawText(" ${Format.distUnit}", PAD + big.measureText(km), y, unit)
        y += 64f
        val line = paint(f.mono, 36f, ink, o, spacing = 0.12f, alpha = 0.85f)
        val text = "${r.sport.label.uppercase()}  ·  ${Format.duration(r.movingMs)}  ·  ${paceOrSpeed(r).uppercase()}"
        fitLine(line, text, W - 2 * PAD, minSize = 26f)
        y += 36f
        c.drawText(text, PAD, y, line)
        return crop(bmp, (y + PAD).toInt())
    }

    private fun minimal(f: Fonts, r: ActivityRecord, o: ShareOptions): Bitmap {
        val bmp = Bitmap.createBitmap(W, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val ink = o.ink.argb
        val cx = W / 2f
        var y = PAD

        val top = paint(f.mono, 40f, ink, o, spacing = 0.3f, alpha = 0.85f).apply { textAlign = Paint.Align.CENTER }
        y += 40f
        c.drawText("${r.sport.label.uppercase()} · ${dateShort(r.startTimeMs).uppercase()}", cx, y, top)
        y += 20f

        val big = paint(f.bold, 320f, ink, o, spacing = -0.05f, tnum = true).apply { textAlign = Paint.Align.CENTER }
        fitLine(big, Format.km(r.distanceM), W - 2 * PAD, minSize = 160f)
        y += -big.fontMetrics.ascent * 0.9f
        c.drawText(Format.km(r.distanceM), cx, y, big)
        y += 60f

        val kms = paint(f.monoBold, 44f, ink, o, spacing = 0.42f).apply { textAlign = Paint.Align.CENTER }
        y += 44f
        c.drawText(Format.distWord, cx, y, kms)
        y += 40f

        val sub = paint(f.regular, 54f, ink, o, alpha = 0.85f).apply { textAlign = Paint.Align.CENTER }
        val subText = "${Format.duration(r.movingMs)}  ·  ${paceOrSpeed(r)}"
        fitLine(sub, subText, W - 2 * PAD, minSize = 36f)
        y += 54f
        c.drawText(subText, cx, y, sub)
        return crop(bmp, (y + PAD).toInt())
    }

    private fun card(f: Fonts, r: ActivityRecord, o: ShareOptions, photo: Bitmap? = null): Bitmap {
        val h = 1350
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val plain = o.copy(shadow = false)
        c.drawColor(BG)

        // Route panel: your photo, or a faint grid
        val panel = RectF(0f, 0f, W.toFloat(), 600f)
        if (photo != null) {
            drawCover(c, photo, panel)
            c.drawRect(
                0f, panel.bottom - 220f, W.toFloat(), panel.bottom,
                Paint().apply { shader = LinearGradient(0f, panel.bottom - 220f, 0f, panel.bottom, BG and 0x00FFFFFF, BG, Shader.TileMode.CLAMP) },
            )
        } else {
            c.drawRect(panel, Paint().apply { color = SURFACE })
            val grid = Paint().apply { color = HAIRLINE; strokeWidth = 2f }
            var gx = 0f
            while (gx < W) { c.drawLine(gx, 0f, gx, panel.bottom, grid); gx += 72f }
            var gy = 0f
            while (gy < panel.bottom) { c.drawLine(0f, gy, W.toFloat(), gy, grid); gy += 72f }
        }
        if (r.points.size >= 2 && (photo == null || o.showRoute)) {
            drawRoute(c, r.points, RectF(PAD + 40f, 64f, W - PAD - 40f, panel.bottom - 64f), ACCENT, 14f, glow = true)
        }

        var y = panel.bottom + 64f
        y += 32f
        val dateLine = "${r.sport.label.uppercase()} · ${dateLong(r.startTimeMs).uppercase()}"
        val datePaint = paint(f.mono, 32f, MUTED, plain, spacing = 0.16f)
        fitLine(datePaint, dateLine, W - 2 * PAD, minSize = 24f)
        c.drawText(dateLine, PAD, y, datePaint)
        y += 18f
        val title = paint(f.bold, 64f, INK, plain)
        y += 64f
        c.drawText(ellipsize(r.title, title, W - 2 * PAD), PAD, y, title)

        val big = paint(f.bold, 190f, if (o.ink == Ink.YELLOW) ACCENT else INK, plain, spacing = -0.04f, tnum = true)
        val km = Format.km(r.distanceM)
        val unit = paint(f.regular, 70f, MUTED, plain)
        fitHero(big, unit, km, W - 2 * PAD)
        y += 30f + -big.fontMetrics.ascent * 0.9f
        c.drawText(km, PAD, y, big)
        c.drawText(" ${Format.distUnit}", PAD + big.measureText(km), y, unit)
        y += 56f

        statRow(c, f, plain.copy(ink = Ink.WHITE), r, y, labelColor = MUTED, valueColor = INK)

        // Footer
        val footY = h - PAD
        c.drawText("MERIDIAN", PAD, footY, paint(f.monoBold, 34f, ACCENT, plain, spacing = 0.32f))
        drawMark(c, W - PAD - 28f, footY - 12f, 28f)
        return bmp
    }

    /** TIME · PACE/SPEED · CLIMB in three columns starting at [top]. */
    private fun statRow(
        c: Canvas,
        f: Fonts,
        o: ShareOptions,
        r: ActivityRecord,
        top: Float,
        labelColor: Int = o.ink.argb,
        valueColor: Int = o.ink.argb,
    ) {
        val col = (W - 2 * PAD) / 3f
        val items = listOf(
            "TIME" to Format.duration(r.movingMs),
            (if (r.sport.showsSpeed) Format.speedUnit.uppercase() else "PACE ${Format.paceUnit.uppercase()}") to
                (if (r.sport.showsSpeed) Format.speedKmh(r.avgSpeed) else Format.pace(r.avgPaceSecPerKm)),
            "CLIMB M" to Format.meters(r.elevationGainM),
        )
        val label = paint(f.mono, 32f, labelColor, o, spacing = 0.18f, alpha = if (labelColor == o.ink.argb) 0.8f else 1f)
        val value = paint(f.bold, 100f, valueColor, o, spacing = -0.02f, tnum = true)
        // Long times such as 3:27:52 used to run into the pace column; columns now make room.
        val layout = StatLayout.columns(PAD, col * 3, baseSize = 100f, minSize = 60f, minGap = 44f) { size ->
            value.textSize = size
            items.map { (l, v) -> max(label.measureText(l), value.measureText(v)) }
        }
        value.textSize = layout.size
        items.forEachIndexed { i, (l, v) ->
            val x = layout.xs[i]
            c.drawText(l, x, top + 32f, label)
            c.drawText(v, x, top + 32f + 18f + 96f * (layout.size / 100f), value)
        }
    }

    // ---- Review image (1080 x 1920, story-sized) ----------------------------------------------

    fun renderReview(context: Context, review: Review): Bitmap {
        val f = Fonts(context)
        val h = 1920
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val plain = ShareOptions(shadow = false)
        val s = review.summary
        c.drawColor(BG)

        // Yellow header
        c.drawRect(RectF(0f, 0f, W.toFloat(), 820f), Paint().apply { color = ACCENT })
        c.drawText("MERIDIAN", PAD, PAD + 60f, paint(f.monoBold, 38f, BG, plain, spacing = 0.32f))
        val name = if (review.period == Period.YEAR) "${review.start.year}"
        else review.start.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val nameSize = if (review.period == Period.YEAR) 330f else 190f
        val namePaint = paint(f.bold, nameSize, BG, plain, spacing = -0.05f)
        c.drawText(name, PAD - 8f, 620f, namePaint)
        c.drawText(
            if (review.period == Period.YEAR) "MY YEAR IN MOTION" else "MY ${review.start.year} · MONTH IN MOTION",
            PAD, 730f, paint(f.monoBold, 40f, BG, plain, spacing = 0.28f),
        )

        // Stats grid
        val label = paint(f.mono, 32f, MUTED, plain, spacing = 0.18f)
        val value = paint(f.bold, 108f, INK, plain, spacing = -0.03f, tnum = true)
        val unit = paint(f.regular, 48f, MUTED, plain)
        val colW = (W - 2 * PAD) / 2f
        val cells = listOf(
            Triple("DISTANCE", Format.dist1(s.distanceM), Format.distUnit),
            Triple("TIME", hoursMinutes(s.movingMs), null),
            Triple("ACTIVITIES", "${s.count}", null),
            Triple("CLIMB", String.format(Locale.US, "%,d", s.elevationM.toInt()), "m"),
        )
        cells.forEachIndexed { i, (l, v, u) ->
            val x = PAD + (i % 2) * colW
            val top = 920f + (i / 2) * 250f
            c.drawText(l, x, top, label)
            value.textSize = 108f
            unit.textSize = 48f
            fitHero(value, unit, v, colW - 32f, u?.let { " $it" } ?: "")
            c.drawText(v, x, top + 130f, value)
            if (u != null) c.drawText(" $u", x + value.measureText(v), top + 130f, unit)
        }

        // Bars
        val chartTop = 1440f
        val chartBottom = 1700f
        val buckets = s.buckets
        val maxV = max(buckets.maxOfOrNull { it.distanceM } ?: 0.0, 1.0)
        val slot = (W - 2 * PAD) / buckets.size
        val barW = min(slot * 0.62f, 44f)
        val peak = review.peakBucket
        buckets.forEachIndexed { i, b ->
            val cx = PAD + slot * i + slot / 2
            val bh = if (b.distanceM > 0) max((b.distanceM / maxV * (chartBottom - chartTop)).toFloat(), barW) else barW * 0.5f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when {
                    b == peak -> ACCENT
                    b.distanceM > 0 -> BAR
                    else -> HAIRLINE
                }
            }
            c.drawRoundRect(RectF(cx - barW / 2, chartBottom - bh, cx + barW / 2, chartBottom), barW / 2, barW / 2, p)
        }
        if (peak != null) {
            c.drawText(
                "${if (review.period == Period.YEAR) "BIGGEST MONTH" else "BIGGEST DAY"} · ${peak.fullLabel.uppercase()}",
                PAD, chartBottom + 70f, paint(f.mono, 32f, MUTED, plain, spacing = 0.16f),
            )
        }

        drawMark(c, W - PAD - 30f, h - PAD - 14f, 30f)
        return bmp
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun paint(
        tf: Typeface,
        size: Float,
        color: Int,
        o: ShareOptions,
        spacing: Float = 0f,
        alpha: Float = 1f,
        tnum: Boolean = false,
    ) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = tf
        textSize = size
        this.color = color
        this.alpha = (alpha * 255).toInt()
        letterSpacing = spacing
        if (tnum) fontFeatureSettings = "tnum"
        if (o.shadow && o.layout.transparent) {
            val shadowColor = if (o.ink == Ink.BLACK) 0x66FFFFFF else 0x8C000000.toInt()
            setShadowLayer(size * 0.06f + 6f, 0f, size * 0.015f + 2f, shadowColor)
        }
    }

    /** Shrinks a big number and its unit together until both fit in [maxWidth]. */
    private fun fitHero(big: Paint, unit: Paint, text: String, maxWidth: Float, unitText: String = " ${Format.distUnit}") {
        val base = big.textSize
        val ratio = unit.textSize / base
        val size = StatLayout.fit(base, base * 0.45f, maxWidth) { s ->
            big.textSize = s
            unit.textSize = s * ratio
            big.measureText(text) + unit.measureText(unitText)
        }
        big.textSize = size
        unit.textSize = size * ratio
    }

    /** Shrinks one line of text until it fits in [maxWidth]. */
    private fun fitLine(p: Paint, text: String, maxWidth: Float, minSize: Float) {
        p.textSize = StatLayout.fit(p.textSize, minSize, maxWidth) { s ->
            p.textSize = s
            p.measureText(text)
        }
    }

    private fun routeColor(o: ShareOptions) = if (o.ink == Ink.WHITE) ACCENT else o.ink.argb

    private fun drawRoute(c: Canvas, points: List<TrackPoint>, box: RectF, color: Int, width: Float, glow: Boolean) {
        val midLat = points.map { it.lat }.average()
        val kx = cos(midLat * PI / 180.0)
        val xs = points.map { it.lon * kx }
        val ys = points.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val spanX = max(maxX - minX, 1e-6)
        val spanY = max(maxY - minY, 1e-6)
        val scale = min((box.width() - width * 2) / spanX, (box.height() - width * 2) / spanY)
        val offX = box.left + (box.width() - spanX * scale) / 2
        val offY = box.top + (box.height() - spanY * scale) / 2
        fun px(i: Int) = (offX + (xs[i] - minX) * scale).toFloat()
        fun py(i: Int) = (offY + (maxY - ys[i]) * scale).toFloat()

        val path = Path()
        var seg = -1
        points.forEachIndexed { i, p ->
            if (p.segment != seg) path.moveTo(px(i), py(i)) else path.lineTo(px(i), py(i))
            seg = p.segment
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            strokeWidth = width
            if (glow) setShadowLayer(width * 1.6f, 0f, width * 0.3f, 0x73000000)
        }
        c.drawPath(path, stroke)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.style = Paint.Style.FILL
        dot.color = color
        c.drawCircle(px(points.lastIndex), py(points.lastIndex), width * 1.25f, dot)
        dot.style = Paint.Style.STROKE
        dot.strokeWidth = width * 0.55f
        c.drawCircle(px(0), py(0), width * 1.1f, dot)
    }

    /** The Meridian globe mark, centred at (cx, cy). */
    private fun drawMark(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.1f; color = 0x66F4F2EA }
        c.drawCircle(cx, cy, r, p)
        p.color = ACCENT
        p.strokeWidth = r * 0.2f
        c.drawOval(RectF(cx - r * 0.44f, cy - r, cx + r * 0.44f, cy + r), p)
    }

    private fun crop(bmp: Bitmap, height: Int): Bitmap {
        val h = height.coerceIn(1, bmp.height)
        return if (h == bmp.height) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, h)
    }

    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun paceOrSpeed(r: ActivityRecord) =
        if (r.sport.showsSpeed) "${Format.speedKmh(r.avgSpeed)} ${Format.speedUnit}" else "${Format.pace(r.avgPaceSecPerKm)} ${Format.paceUnit}"

    private fun hoursMinutes(ms: Long): String {
        val mins = ms / 60_000
        return "${mins / 60}h ${String.format(Locale.US, "%02d", mins % 60)}m"
    }

    private val shortFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private val longFmt = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.getDefault())
    private fun dateShort(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(shortFmt)
    private fun dateLong(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(longFmt)
}
