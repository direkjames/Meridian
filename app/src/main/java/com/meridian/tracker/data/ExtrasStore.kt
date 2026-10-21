package com.meridian.tracker.data

import android.content.Context
import android.util.Log
import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.Sport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** A route drawn in the route builder. */
data class PlannedRoute(
    val id: String,
    val name: String,
    val points: List<Pair<Double, Double>>,
    val createdMs: Long,
) {
    val distanceM: Double
        get() = points.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a.first, a.second, b.first, b.second) }
}

/**
 * Small things that aren't activities: the training plan, goals, saved routes and which badges
 * have already been celebrated. Stored as JSON in app storage and included in backups.
 */
object ExtrasStore {

    private const val TAG = "ExtrasStore"
    const val DIR = "extras"
    private const val PLAN = "plan.json"
    private const val GOALS = "goals.json"
    private const val ROUTES = "routes.json"
    private const val SEEN = "badges_seen.json"
    private const val GEAR = "gear.json"
    val FILES = listOf(PLAN, GOALS, ROUTES, SEEN, GEAR)

    private lateinit var dir: File

    private val _plan = MutableStateFlow<TrainingPlan?>(null)
    val plan: StateFlow<TrainingPlan?> = _plan.asStateFlow()

    private val _goals = MutableStateFlow<List<Goal>>(emptyList())
    val goals: StateFlow<List<Goal>> = _goals.asStateFlow()

    private val _routes = MutableStateFlow<List<PlannedRoute>>(emptyList())
    val routes: StateFlow<List<PlannedRoute>> = _routes.asStateFlow()

    private val _seenBadges = MutableStateFlow<Set<String>>(emptySet())
    val seenBadges: StateFlow<Set<String>> = _seenBadges.asStateFlow()

    private val _gear = MutableStateFlow<List<Gear>>(emptyList())
    /** Shoes, bikes and other gear, newest first. */
    val gear: StateFlow<List<Gear>> = _gear.asStateFlow()

    private val _planCompletedMs = MutableStateFlow<Long?>(null)
    val planCompletedMs: StateFlow<Long?> = _planCompletedMs.asStateFlow()

    /** Called after any change (used for automatic backups). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        dir = File(context.filesDir, DIR).apply { mkdirs() }
        reload()
    }

    /** Re-reads everything from disk (also used after a restore). */
    fun reload() {
        _plan.value = read(PLAN)?.let { runCatching { planFromJson(JSONObject(it)) }.getOrNull() }
        _goals.value = read(GOALS)?.let { runCatching { goalsFromJson(JSONArray(it)) }.getOrNull() }.orEmpty()
        _routes.value = read(ROUTES)?.let { runCatching { routesFromJson(JSONArray(it)) }.getOrNull() }.orEmpty()
        _gear.value = read(GEAR)?.let { runCatching { gearFromJson(JSONArray(it)) }.getOrNull() }.orEmpty()
        read(SEEN)?.let {
            runCatching {
                val o = JSONObject(it)
                val ids = o.getJSONArray("ids")
                _seenBadges.value = List(ids.length()) { i -> ids.getString(i) }.toSet()
                _planCompletedMs.value = if (o.has("planCompleted")) o.getLong("planCompleted") else null
            }
        }
    }

    // ---- Plan ---------------------------------------------------------------------------------

    fun setPlan(plan: TrainingPlan?) {
        _plan.value = plan
        if (plan == null) File(dir, PLAN).delete() else write(PLAN, planToJson(plan).toString())
        changed()
    }

    fun markPlanCompleted(ms: Long) {
        if (_planCompletedMs.value != null) return
        _planCompletedMs.value = ms
        saveSeen()
    }

    // ---- Goals --------------------------------------------------------------------------------

    fun saveGoal(goal: Goal) {
        _goals.value = _goals.value.filterNot { it.id == goal.id } + goal
        write(GOALS, goalsToJson(_goals.value).toString())
        changed()
    }

    fun deleteGoal(id: String) {
        _goals.value = _goals.value.filterNot { it.id == id }
        write(GOALS, goalsToJson(_goals.value).toString())
        changed()
    }

    // ---- Routes -------------------------------------------------------------------------------

    fun saveRoute(route: PlannedRoute) {
        _routes.value = (_routes.value.filterNot { it.id == route.id } + route).sortedByDescending { it.createdMs }
        write(ROUTES, routesToJson(_routes.value).toString())
        changed()
    }

    fun deleteRoute(id: String) {
        _routes.value = _routes.value.filterNot { it.id == id }
        write(ROUTES, routesToJson(_routes.value).toString())
        changed()
    }

    // ---- Gear ---------------------------------------------------------------------------------

    @Synchronized
    fun saveGear(item: Gear) {
        _gear.value = (_gear.value.filterNot { it.id == item.id } + item).sortedByDescending { it.createdMs }
        write(GEAR, gearToJson(_gear.value).toString())
        changed()
    }

    /** Activities keep the id, but deleted gear is simply no longer shown or counted. */
    @Synchronized
    fun deleteGear(id: String) {
        _gear.value = _gear.value.filterNot { it.id == id }
        write(GEAR, gearToJson(_gear.value).toString())
        changed()
    }

    // ---- Badges -------------------------------------------------------------------------------

    fun markBadgesSeen(ids: Collection<String>) {
        if (_seenBadges.value.containsAll(ids)) return
        _seenBadges.value = _seenBadges.value + ids
        saveSeen()
    }

    private fun saveSeen() {
        val o = JSONObject().put("ids", JSONArray(_seenBadges.value.toList()))
        _planCompletedMs.value?.let { o.put("planCompleted", it) }
        write(SEEN, o.toString())
        changed()
    }

    // ---- Backup support -------------------------------------------------------------------------

    /** File name → JSON text, for backups. */
    fun exportFiles(): Map<String, String> = FILES.mapNotNull { name -> read(name)?.let { name to it } }.toMap()

    /** Restores files that are missing here; goals and routes are merged by id. */
    fun importFiles(files: Map<String, String>) {
        files[PLAN]?.let { if (_plan.value == null) write(PLAN, it) }
        files[GOALS]?.let { text ->
            runCatching { goalsFromJson(JSONArray(text)) }.getOrNull()?.let { incoming ->
                val merged = (_goals.value + incoming.filter { g -> _goals.value.none { it.id == g.id } })
                write(GOALS, goalsToJson(merged).toString())
            }
        }
        files[ROUTES]?.let { text ->
            runCatching { routesFromJson(JSONArray(text)) }.getOrNull()?.let { incoming ->
                val merged = (_routes.value + incoming.filter { r -> _routes.value.none { it.id == r.id } })
                write(ROUTES, routesToJson(merged).toString())
            }
        }
        files[SEEN]?.let { if (read(SEEN) == null) write(SEEN, it) }
        files[GEAR]?.let { text ->
            runCatching { gearFromJson(JSONArray(text)) }.getOrNull()?.let { incoming ->
                val merged = (_gear.value + incoming.filter { g -> _gear.value.none { it.id == g.id } })
                write(GEAR, gearToJson(merged).toString())
            }
        }
        reload()
        changed()
    }

    // ---- JSON ---------------------------------------------------------------------------------

    private fun planToJson(p: TrainingPlan): JSONObject {
        val c = p.config
        return JSONObject()
            .put("id", p.id)
            .put("created", p.createdMs)
            .put("name", c.name)
            .put("mode", c.mode.name)
            .put("target", c.targetType.name)
            .put("eventM", c.eventDistanceM)
            .put("start", c.startDate.toString())
            .put("event", c.eventDate.toString())
            .put("days", c.daysPerWeek)
            .put("longDay", c.longDay.value)
            .put("currentLongM", c.currentLongM)
            .put("pace", c.paceSecPerKm)
            .put("remH", c.reminderHour)
            .put("remM", c.reminderMinute)
            .put("nudge", c.eveningNudge)
    }

    private fun planFromJson(o: JSONObject): TrainingPlan {
        val config = PlanConfig(
            name = o.getString("name"),
            mode = PlanMode.valueOf(o.getString("mode")),
            targetType = TargetType.valueOf(o.getString("target")),
            eventDistanceM = o.getDouble("eventM"),
            startDate = LocalDate.parse(o.getString("start")),
            eventDate = LocalDate.parse(o.getString("event")),
            daysPerWeek = o.getInt("days"),
            longDay = DayOfWeek.of(o.getInt("longDay")),
            currentLongM = o.getDouble("currentLongM"),
            paceSecPerKm = o.getDouble("pace"),
            reminderHour = o.optInt("remH", 6),
            reminderMinute = o.optInt("remM", 0),
            eveningNudge = o.optBoolean("nudge", true),
        )
        // The plan is rebuilt from its settings, so it's always consistent.
        return PlanGenerator.generate(config, o.getString("id"), o.getLong("created"))
    }

    private fun goalsToJson(goals: List<Goal>) = JSONArray().apply {
        goals.forEach { g ->
            put(
                JSONObject()
                    .put("id", g.id)
                    .put("period", g.period.name)
                    .put("metric", g.metric.name)
                    .put("target", g.target)
                    .put("sport", g.sport?.name ?: JSONObject.NULL)
            )
        }
    }

    private fun goalsFromJson(a: JSONArray): List<Goal> = List(a.length()) { i ->
        val o = a.getJSONObject(i)
        Goal(
            id = o.getString("id"),
            period = Period.valueOf(o.getString("period")),
            metric = GoalMetric.valueOf(o.getString("metric")),
            target = o.getDouble("target"),
            sport = if (o.isNull("sport")) null else runCatching { Sport.valueOf(o.getString("sport")) }.getOrNull(),
        )
    }

    private fun gearToJson(items: List<Gear>) = JSONArray().apply {
        items.forEach { g ->
            put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("type", g.type.name)
                    .put("limit", g.limitM ?: JSONObject.NULL)
                    .put("start", g.startM)
                    .put("retired", g.retired)
                    .put("created", g.createdMs)
            )
        }
    }

    private fun gearFromJson(a: JSONArray): List<Gear> = List(a.length()) { i ->
        runCatching {
            val o = a.getJSONObject(i)
            Gear(
                id = o.getString("id"),
                name = GearMath.cleanName(o.getString("name")).ifEmpty { "Gear" },
                type = runCatching { GearType.valueOf(o.getString("type")) }.getOrDefault(GearType.OTHER),
                limitM = if (o.isNull("limit")) null else o.getDouble("limit").takeIf { it > 0 && !it.isNaN() },
                startM = o.optDouble("start", 0.0).takeIf { !it.isNaN() && it >= 0 } ?: 0.0,
                retired = o.optBoolean("retired", false),
                createdMs = o.optLong("created", 0L),
            )
        }.getOrNull()
    }.filterNotNull().distinctBy { it.id }.sortedByDescending { it.createdMs }

    private fun routesToJson(routes: List<PlannedRoute>) = JSONArray().apply {
        routes.forEach { r ->
            put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("created", r.createdMs)
                    .put("points", JSONArray().apply { r.points.forEach { (lat, lon) -> put(JSONArray().put(lat).put(lon)) } })
            )
        }
    }

    private fun routesFromJson(a: JSONArray): List<PlannedRoute> = List(a.length()) { i ->
        val o = a.getJSONObject(i)
        val pts = o.getJSONArray("points")
        PlannedRoute(
            id = o.getString("id"),
            name = o.getString("name"),
            points = List(pts.length()) { j -> pts.getJSONArray(j).let { it.getDouble(0) to it.getDouble(1) } },
            createdMs = o.getLong("created"),
        )
    }

    // ---- Files --------------------------------------------------------------------------------

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.let {
        try {
            it.readText()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $name", e)
            null
        }
    }

    private fun write(name: String, text: String) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write $name", e)
        }
    }

    private fun changed() {
        onChanged?.invoke()
    }
}
