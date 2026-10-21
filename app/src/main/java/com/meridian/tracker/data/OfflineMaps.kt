package com.meridian.tracker.data

import android.content.Context
import android.util.Log
import com.meridian.tracker.ui.components.DARK_STYLE_URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

data class OfflineArea(
    val id: Long,
    val name: String,
    val createdMs: Long,
    val progress: Float,
    val sizeBytes: Long,
    val complete: Boolean,
    val error: String? = null,
)

/**
 * Downloaded map areas for use with no signal. MapLibre stores the tiles in its own database;
 * this keeps a small observable list for the UI. All callbacks arrive on the main thread.
 */
object OfflineMaps {

    private const val TAG = "OfflineMaps"
    const val MIN_ZOOM = 10
    const val MAX_ZOOM = 15
    const val MAX_TILES = 6_000L

    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas.asStateFlow()

    private val regions = mutableMapOf<Long, OfflineRegion>()
    private var loaded = false

    fun refresh(context: Context) {
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                regions.clear()
                val list = offlineRegions.orEmpty().map { region ->
                    regions[region.id] = region
                    val (name, created) = readMetadata(region)
                    watch(region)
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status == null) return
                            update(region.id, status)
                            // Resume anything interrupted, e.g. by closing the app mid-download.
                            if (!status.isComplete) region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        }

                        override fun onError(error: String?) {
                            Log.w(TAG, "Status error: $error")
                        }
                    })
                    OfflineArea(region.id, name, created, 0f, 0L, false)
                }
                _areas.value = list.sortedByDescending { it.createdMs }
                loaded = true
            }

            override fun onError(error: String) {
                Log.w(TAG, "List error: $error")
            }
        })
    }

    fun ensureLoaded(context: Context) {
        if (!loaded) refresh(context)
    }

    fun download(context: Context, bounds: LatLngBounds, name: String) {
        val density = context.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(
            DARK_STYLE_URL,
            bounds,
            MIN_ZOOM.toDouble(),
            MAX_ZOOM.toDouble(),
            density,
        )
        val created = System.currentTimeMillis()
        val metadata = JSONObject().put("name", name).put("created", created).toString().toByteArray()

        OfflineManager.getInstance(context).createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    regions[offlineRegion.id] = offlineRegion
                    _areas.update { listOf(OfflineArea(offlineRegion.id, name, created, 0f, 0L, false)) + it }
                    watch(offlineRegion)
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    Log.w(TAG, "Create error: $error")
                }
            },
        )
    }

    fun delete(id: Long) {
        val region = regions[id] ?: return
        region.setObserver(null)
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                regions.remove(id)
                _areas.update { list -> list.filterNot { it.id == id } }
            }

            override fun onError(error: String) {
                Log.w(TAG, "Delete error: $error")
            }
        })
    }

    private fun watch(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                update(region.id, status)
                if (status.isComplete) region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }

            override fun onError(error: OfflineRegionError) {
                Log.w(TAG, "Download error: ${error.message}")
                _areas.update { list ->
                    list.map { if (it.id == region.id) it.copy(error = "Download paused. It will retry when you're online.") else it }
                }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                _areas.update { list ->
                    list.map { if (it.id == region.id) it.copy(error = "Area too large. Delete it and pick a smaller one.") else it }
                }
            }
        })
    }

    private fun update(id: Long, status: OfflineRegionStatus) {
        val progress = if (status.requiredResourceCount > 0) {
            (status.completedResourceCount.toDouble() / status.requiredResourceCount).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        _areas.update { list ->
            list.map {
                if (it.id == id) {
                    it.copy(
                        progress = if (status.isComplete) 1f else progress,
                        sizeBytes = status.completedResourceSize,
                        complete = status.isComplete,
                        error = if (status.isComplete) null else it.error,
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun readMetadata(region: OfflineRegion): Pair<String, Long> = try {
        val json = JSONObject(String(region.metadata))
        json.optString("name", "Saved area") to json.optLong("created", 0L)
    } catch (e: Exception) {
        "Saved area" to 0L
    }
}
