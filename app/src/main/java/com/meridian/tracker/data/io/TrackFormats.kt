package com.meridian.tracker.data.io

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.HeartRate
import com.meridian.tracker.tracking.Sport
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** GPX and TCX reading, and GPX writing. Uses the standard XML parser, so it's unit-testable. */
object TrackFormats {

    // ---- Reading -------------------------------------------------------------------------------

    fun parseGpx(input: InputStream): ParsedTrack {
        val doc = documentBuilder().parse(input)
        val root = doc.documentElement
        val trk = root.childElements("trk").firstOrNull()
        val name = trk?.childText("name") ?: root.childElements("metadata").firstOrNull()?.childText("name")
        val type = trk?.childText("type")
        val points = ArrayList<RawPoint>()
        val nodes = doc.getElementsByTagNameNS("*", "trkpt").takeIf { it.length > 0 }
            ?: doc.getElementsByTagName("trkpt")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
            val time = el.childText("time")?.let { parseTime(it) } ?: continue
            val ele = el.childText("ele")?.toDoubleOrNull()
            // Garmin's TrackPointExtension: <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>
            val hr = el.descendantText("hr")?.toDoubleOrNull()?.toInt()
            points.add(RawPoint(lat, lon, ele, time, hr))
        }
        return ParsedTrack(points, name, RecordBuilder.sportFrom(type))
    }

    fun parseTcx(input: InputStream): ParsedTrack {
        val doc = documentBuilder().parse(input)
        val activity = doc.getElementsByTagNameNS("*", "Activity").item(0) as? Element
        val sport = RecordBuilder.sportFrom(activity?.getAttribute("Sport"))
        val points = ArrayList<RawPoint>()
        val nodes = doc.getElementsByTagNameNS("*", "Trackpoint")
        for (i in 0 until nodes.length) {
            val tp = nodes.item(i) as? Element ?: continue
            val time = tp.childText("Time")?.let { parseTime(it) } ?: continue
            val pos = tp.childElements("Position").firstOrNull() ?: continue
            val lat = pos.childText("LatitudeDegrees")?.toDoubleOrNull() ?: continue
            val lon = pos.childText("LongitudeDegrees")?.toDoubleOrNull() ?: continue
            val ele = tp.childText("AltitudeMeters")?.toDoubleOrNull()
            val hr = tp.childElements("HeartRateBpm").firstOrNull()?.childText("Value")?.toDoubleOrNull()?.toInt()
            points.add(RawPoint(lat, lon, ele, time, hr))
        }
        return ParsedTrack(points, null, sport)
    }

    fun parseTime(text: String): Long? {
        val t = text.trim()
        return runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t + "Z").toEpochMilli() }.getOrNull()
    }

    // ---- Writing -------------------------------------------------------------------------------

    private val isoFormat: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun writeGpx(record: ActivityRecord): String {
        val type = when (record.sport) {
            Sport.RUN, Sport.TRAIL_RUN, Sport.TREADMILL -> "running"
            Sport.HIKE -> "hiking"
            Sport.RIDE -> "cycling"
            Sport.WALK -> "walking"
        }
        val sb = StringBuilder(record.points.size * 110 + 600)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Meridian" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""").append('\n')
        sb.append("  <metadata><name>").append(escape(record.title)).append("</name><time>")
            .append(isoFormat.format(Instant.ofEpochMilli(record.startTimeMs))).append("</time></metadata>\n")
        sb.append("  <trk>\n    <name>").append(escape(record.title)).append("</name>\n    <type>")
            .append(type).append("</type>\n")
        record.points.groupBy { it.segment }.toSortedMap().values.forEach { seg ->
            sb.append("    <trkseg>\n")
            seg.forEach { p ->
                sb.append("      <trkpt lat=\"").append(String.format(Locale.US, "%.7f", p.lat))
                    .append("\" lon=\"").append(String.format(Locale.US, "%.7f", p.lon)).append("\">")
                p.alt?.let { sb.append("<ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>") }
                sb.append("<time>").append(isoFormat.format(Instant.ofEpochMilli(p.time))).append("</time>")
                HeartRate.at(record.heartRate, p.time)?.let {
                    sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it)
                        .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                }
                sb.append("</trkpt>\n")
            }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    fun fileName(record: ActivityRecord): String {
        val date = Instant.ofEpochMilli(record.startTimeMs).toString().take(10)
        val safe = record.title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40)
        return "$date-$safe.gpx"
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun documentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder()

    private fun Element.childElements(localName: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.nodeName) == localName) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    /** Text of the first descendant element with this local name (any namespace). */
    private fun Element.descendantText(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    private fun Element.childText(localName: String): String? =
        childElements(localName).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
