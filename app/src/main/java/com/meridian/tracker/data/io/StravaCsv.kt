package com.meridian.tracker.data.io

/** Reads activities.csv from a Strava "Download your data" archive. */
object StravaCsv {

    data class Entry(val name: String?, val type: String?)

    /** Map of file path inside the archive (e.g. "activities/123.fit.gz") to its name and type. */
    fun parse(text: String): Map<String, Entry> {
        val rows = parseRows(text)
        if (rows.isEmpty()) return emptyMap()
        val header = rows.first().map { it.trim().lowercase() }
        val nameIdx = header.indexOf("activity name")
        val typeIdx = header.indexOf("activity type")
        val fileIdx = header.indexOf("filename")
        if (fileIdx < 0) return emptyMap()
        return rows.drop(1).mapNotNull { row ->
            val file = row.getOrNull(fileIdx)?.trim().orEmpty()
            if (file.isEmpty()) return@mapNotNull null
            file to Entry(row.getOrNull(nameIdx)?.trim(), row.getOrNull(typeIdx)?.trim())
        }.toMap()
    }

    /** RFC 4180 style: quoted fields may contain commas, newlines and doubled quotes. */
    fun parseRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(field.toString()); field.setLength(0) }
                    '\n', '\r' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(field.toString()); field.setLength(0)
                        if (row.any { it.isNotEmpty() }) rows.add(row)
                        row = ArrayList()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }
}
