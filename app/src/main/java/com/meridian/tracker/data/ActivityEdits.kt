package com.meridian.tracker.data

import com.meridian.tracker.tracking.TrackingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Changes made from the activity screen (photos, videos, gear). They go to the unsaved draft
 * while it's on the Activity Complete screen, or to the saved activity otherwise.
 */
object ActivityEdits {

    suspend fun edit(id: String, transform: (ActivityRecord) -> ActivityRecord): ActivityRecord? {
        val draft = TrackingManager.draft.value
        if (draft != null && draft.id == id && ActivityStore.find(id) == null) {
            return TrackingManager.updateDraft { if (it.id == id) transform(it) else it }
        }
        return ActivityStore.update(id, transform)
    }

    /** Attaches a stored photo/video. If it doesn't fit (limit reached, activity gone), the file is removed. */
    suspend fun addMedia(id: String, item: MediaItem): Boolean {
        var added = false
        edit(id) { r ->
            if (r.media.size >= ActivityMedia.MAX_PER_ACTIVITY || r.media.any { it.id == item.id }) r
            else {
                added = true
                r.copy(media = r.media + item)
            }
        }
        if (!added) withContext(Dispatchers.IO) { ActivityMedia.delete(id, item) }
        return added
    }

    suspend fun removeMedia(id: String, item: MediaItem) {
        edit(id) { r -> r.copy(media = r.media.filterNot { it.id == item.id }) }
        withContext(Dispatchers.IO) { ActivityMedia.delete(id, item) }
    }

    suspend fun setSport(id: String, sport: com.meridian.tracker.tracking.Sport) {
        edit(id) { r -> ActivityEditing.changeSport(r, sport) }
    }

    suspend fun setNote(id: String, note: String) {
        val clean = ActivityDetails.cleanNote(note)
        edit(id) { r -> r.copy(note = clean) }
    }

    suspend fun setEffort(id: String, rating: Int?) {
        val clean = ActivityDetails.cleanEffort(rating)
        edit(id) { r -> r.copy(perceivedEffort = clean) }
    }

    /** Adds the weather tag once; never replaces one that's there. */
    suspend fun setWeather(id: String, weather: Weather) {
        edit(id) { r -> if (r.weather == null && WeatherTag.valid(weather)) r.copy(weather = weather) else r }
    }

    suspend fun setFeeling(id: String, feeling: Feeling?) {
        edit(id) { r -> r.copy(feeling = feeling) }
    }

    /**
     * Changes the unsaved draft right now (no waiting), so a note typed a moment ago is part of
     * the activity the instant Save is tapped. Returns false when [id] isn't the open draft.
     */
    fun editDraftNow(id: String, transform: (ActivityRecord) -> ActivityRecord): Boolean {
        val draft = TrackingManager.draft.value
        if (draft == null || draft.id != id || ActivityStore.find(id) != null) return false
        TrackingManager.updateDraft { if (it.id == id) transform(it) else it }
        return true
    }

    suspend fun setGear(id: String, gearIds: List<String>) {
        val clean = gearIds.distinct().take(GearMath.MAX_PER_ACTIVITY)
        edit(id) { r -> r.copy(gearIds = clean) }
    }
}
