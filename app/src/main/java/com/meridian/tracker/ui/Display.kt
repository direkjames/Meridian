package com.meridian.tracker.ui

import com.meridian.tracker.data.BestKind
import com.meridian.tracker.data.BestResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Value and unit for a personal best, e.g. "26:12" or "12.40" + Format.distUnit. */
fun BestResult.display(): Pair<String, String?> = when (spec.kind) {
    BestKind.EFFORT, BestKind.DURATION -> Format.duration(value.toLong()) to null
    BestKind.DISTANCE -> Format.km(value) to Format.distUnit
    BestKind.CLIMB -> Format.meters(value) to "m"
}

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

fun shortDateLabel(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(shortDate)
