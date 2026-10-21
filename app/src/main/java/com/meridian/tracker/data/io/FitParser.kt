package com.meridian.tracker.data.io

import com.meridian.tracker.tracking.Sport
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * A small reader for Garmin FIT activity files (what Strava exports for watch recordings).
 * It reads only what Meridian needs: GPS points, altitude, timestamps and the sport.
 */
object FitParser {

    private const val MSG_SESSION = 18
    private const val MSG_RECORD = 20
    private const val MSG_SPORT = 12

    private const val FIELD_TIMESTAMP = 253
    private const val FIELD_LAT = 0
    private const val FIELD_LON = 1
    private const val FIELD_ALTITUDE = 2
    private const val FIELD_ENHANCED_ALTITUDE = 78
    private const val FIELD_HEART_RATE = 3
    private const val FIELD_SPORT = 5 // session.sport
    private const val FIELD_SPORT_MSG = 0 // sport.sport

    /** Seconds between the Unix epoch and the FIT epoch (1989-12-31T00:00:00Z). */
    private const val FIT_EPOCH_S = 631_065_600L
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0

    private class FieldDef(val num: Int, val size: Int, val baseType: Int)

    private class MessageDef(
        val global: Int,
        val bigEndian: Boolean,
        val fields: List<FieldDef>,
        val devBytes: Int,
    )

    fun parse(input: InputStream): ParsedTrack {
        val data = DataInputStream(input.buffered())
        // ---- File header
        val headerSize = data.readUnsignedByte()
        if (headerSize < 12) throw IllegalArgumentException("Not a FIT file")
        data.readUnsignedByte() // protocol version
        data.readUnsignedShort() // profile version (little-endian, unused)
        val dataSize = readUInt32LE(data)
        val magic = ByteArray(4).also { data.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != ".FIT") throw IllegalArgumentException("Not a FIT file")
        repeat(headerSize - 12) { data.readUnsignedByte() }

        val defs = HashMap<Int, MessageDef>()
        val points = ArrayList<RawPoint>()
        var sport: Sport? = null
        var lastTimestamp = 0L
        var read = 0L

        try {
            while (read < dataSize) {
                val header = data.readUnsignedByte()
                read += 1
                if (header and 0x80 != 0) {
                    // Compressed timestamp header: data message with a 5-bit time offset.
                    val local = (header shr 5) and 0x03
                    val offset = header and 0x1F
                    val def = defs[local] ?: throw IllegalStateException("Missing definition")
                    var ts = (lastTimestamp and 0x1F.inv().toLong()) + offset
                    if (offset < (lastTimestamp and 0x1F)) ts += 0x20
                    lastTimestamp = ts
                    val values = readFields(data, def)
                    read += def.fields.sumOf { it.size } + def.devBytes
                    handle(def, values, ts, points)?.let { sport = it }
                } else if (header and 0x40 != 0) {
                    // Definition message
                    val hasDev = header and 0x20 != 0
                    val local = header and 0x0F
                    data.readUnsignedByte() // reserved
                    val arch = data.readUnsignedByte()
                    val big = arch == 1
                    val global = if (big) data.readUnsignedShort() else readUInt16LE(data)
                    val count = data.readUnsignedByte()
                    read += 5
                    val fields = List(count) {
                        FieldDef(data.readUnsignedByte(), data.readUnsignedByte(), data.readUnsignedByte())
                    }
                    read += count * 3
                    var devBytes = 0
                    if (hasDev) {
                        val devCount = data.readUnsignedByte()
                        read += 1
                        repeat(devCount) {
                            data.readUnsignedByte()
                            devBytes += data.readUnsignedByte()
                            data.readUnsignedByte()
                        }
                        read += devCount * 3
                    }
                    defs[local] = MessageDef(global, big, fields, devBytes)
                } else {
                    // Normal data message
                    val local = header and 0x0F
                    val def = defs[local] ?: throw IllegalStateException("Missing definition")
                    val values = readFields(data, def)
                    read += def.fields.sumOf { it.size } + def.devBytes
                    val ts = values[FIELD_TIMESTAMP]
                    if (ts != null) lastTimestamp = ts
                    handle(def, values, ts ?: lastTimestamp, points)?.let { sport = it }
                }
            }
        } catch (_: EOFException) {
            // Truncated file: keep what was read.
        }
        return ParsedTrack(points, null, sport)
    }

    /** Returns a sport if this message carried one; adds GPS points for record messages. */
    private fun handle(def: MessageDef, values: Map<Int, Long>, timestamp: Long, points: MutableList<RawPoint>): Sport? {
        when (def.global) {
            MSG_RECORD -> {
                val lat = values[FIELD_LAT]?.toInt() ?: return null
                val lon = values[FIELD_LON]?.toInt() ?: return null
                val enhanced = values[FIELD_ENHANCED_ALTITUDE]?.let { it / 5.0 - 500.0 }
                val alt = enhanced ?: values[FIELD_ALTITUDE]?.let { it / 5.0 - 500.0 }
                points.add(
                    RawPoint(
                        lat = lat * SEMICIRCLE_TO_DEG,
                        lon = lon * SEMICIRCLE_TO_DEG,
                        ele = alt,
                        timeMs = (timestamp + FIT_EPOCH_S) * 1000L,
                        hr = values[FIELD_HEART_RATE]?.toInt(),
                    )
                )
            }
            MSG_SESSION -> return values[FIELD_SPORT]?.let { sportFromFit(it.toInt()) }
            MSG_SPORT -> return values[FIELD_SPORT_MSG]?.let { sportFromFit(it.toInt()) }
        }
        return null
    }

    private fun sportFromFit(code: Int): Sport? = when (code) {
        1 -> Sport.RUN
        2 -> Sport.RIDE
        11, 17 -> Sport.WALK // walking, hiking
        else -> null
    }

    /** Reads every field; integer fields (up to 4 bytes) that aren't "invalid" go in the map. */
    private fun readFields(data: DataInputStream, def: MessageDef): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        for (f in def.fields) {
            val bytes = ByteArray(f.size).also { data.readFully(it) }
            if (f.size in 1..4) {
                val signed = (f.baseType and 0x1F) in setOf(1, 3, 5) // sint8, sint16, sint32
                readInt(bytes, def.bigEndian, signed)?.let { v ->
                    if (!isInvalid(v, f.size, signed)) out[f.num] = v
                }
            }
        }
        if (def.devBytes > 0) data.discard(def.devBytes)
        return out
    }

    private fun DataInputStream.discard(n: Int) {
        var left = n
        while (left > 0) { readUnsignedByte(); left-- }
    }

    private fun readInt(b: ByteArray, big: Boolean, signed: Boolean): Long? {
        var v = 0L
        for (i in b.indices) {
            val byte = (if (big) b[i] else b[b.size - 1 - i]).toLong() and 0xFF
            v = (v shl 8) or byte
        }
        if (signed) {
            val bits = b.size * 8
            if (v and (1L shl (bits - 1)) != 0L) v -= (1L shl bits)
        }
        return v
    }

    private fun isInvalid(v: Long, size: Int, signed: Boolean): Boolean = if (signed) {
        when (size) {
            1 -> v == 0x7FL
            2 -> v == 0x7FFFL
            else -> v == 0x7FFFFFFFL
        }
    } else {
        when (size) {
            1 -> v == 0xFFL
            2 -> v == 0xFFFFL
            else -> v == 0xFFFFFFFFL
        }
    }

    private fun readUInt16LE(d: DataInputStream): Int {
        val a = d.readUnsignedByte()
        val b = d.readUnsignedByte()
        return a or (b shl 8)
    }

    private fun readUInt32LE(d: DataInputStream): Long {
        var v = 0L
        for (i in 0 until 4) v = v or (d.readUnsignedByte().toLong() shl (8 * i))
        return v
    }
}
