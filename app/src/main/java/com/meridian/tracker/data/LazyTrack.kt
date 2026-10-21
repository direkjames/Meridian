package com.meridian.tracker.data

import com.meridian.tracker.tracking.TrackPoint
import java.lang.ref.SoftReference

/**
 * The GPS points of a saved activity, read from disk the first time something needs them.
 * Keeping thousands of activities in memory with every point loaded is what runs low-memory
 * phones out of memory, so saved activities only hold their summary until a screen asks for the
 * route. Android may drop the loaded points again when memory is tight; they are simply re-read.
 */
class LazyTrack(
    private val count: Int,
    private val load: () -> List<TrackPoint>,
) : AbstractList<TrackPoint>() {

    @Volatile private var cache: SoftReference<List<TrackPoint>>? = null
    @Volatile private var failed = false

    private fun data(): List<TrackPoint> {
        cache?.get()?.let { return it }
        if (failed) return emptyList()
        val loaded = try {
            load()
        } catch (e: Exception) {
            failed = true
            emptyList()
        } catch (e: OutOfMemoryError) {
            // Not enough memory for this route right now; show it as empty instead of crashing.
            return emptyList()
        }
        cache = SoftReference(loaded)
        return loaded
    }

    /** The size is known up front, so counting points never reads the file. */
    override val size: Int
        get() = if (failed) 0 else cache?.get()?.size ?: count

    override fun get(index: Int): TrackPoint = data()[index]

    // Hold the list for the whole walk, so it can't be dropped half-way through.
    override fun iterator(): Iterator<TrackPoint> = data().iterator()

    // Two copies of the same activity are equal without reading either file.
    override fun equals(other: Any?): Boolean = this === other || (other !is LazyTrack && super.equals(other))

    override fun hashCode(): Int = count
}
