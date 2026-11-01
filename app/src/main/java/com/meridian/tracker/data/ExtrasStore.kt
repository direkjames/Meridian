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
    private const val MONTHLY = "monthly.json"
    val FILES = listOf(PLAN, GOALS, ROUTES, SEEN, GEAR, MONTHLY)

    private lateinit var dir: File

    private val _plan = MutableStateFlow<TrainingPlan?>(null)
    val plan: StateFlow<TrainingPlan?> = _plan.asStateFlow()

    private val _monthly = MutableStateFlow<MonthlyPlan?>(null)
    /** The monthly plan, kept apart from the event plan so you can follow both. */
    val monthly: StateFlow<MonthlyPlan?> = _monthly.asStateFlow()

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
        _monthly.value = read(MONTHLY)?.let { runCatching { monthlyFromJson(JSONObject(it)) }.getOrNull() }
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

    fun setMonthly(plan: MonthlyPlan?) {
        _monthly.value = plan
        if (plan == null) File(dir, MONTHLY).delete() else write(MONTHLY, monthlyToJson(plan).toString())
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
        files[MONTHLY]?.let { if (_monthly.value == null) write(MONTHLY, it) }
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
            // Days you edited. The rest of the plan is rebuilt from the settings above.
            .put("edits", JSONArray().apply { PlanEdits.edits(p).forEach { put(planDayToJson(it)) } })
    }

    private fun planDayToJson(d: PlanDay) = JSONObject()
        .put("date", d.date.toString())
        .put("kind", d.kind.name)
        .put("m", d.distanceM)
        .put("min", d.minutes)
        .put("title", d.title)
        .put("detail", d.detail)
        .put("week", d.week)

    private fun planDayFromJson(o: JSONObject): PlanDay? = runCatching {
        PlanDay(
            date = LocalDate.parse(o.getString("date")),
            kind = SessionKind.valueOf(o.getString("kind")),
            distanceM = o.getDouble("m"),
            minutes = o.getInt("min"),
            title = o.getString("title"),
            detail = o.optString("detail", ""),
            week = o.optInt("week", 0),
        )
    }.getOrNull()

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
        // The plan is rebuilt from its settings, so it's always consistent; then your edits go back on.
        val plan = PlanGenerator.generate(config, o.getString("id"), o.getLong("created"))
        val edits = o.optJSONArray("edits")?.let { a -> List(a.length()) { planDayFromJson(a.getJSONObject(it)) } }.orEmpty().filterNotNull()
        return PlanEdits.restore(plan, edits)
    }

    private fun monthlyToJson(p: MonthlyPlan): JSONObject {
        val c = p.config
        return JSONObject()
            .put("v", 1)
            .put("id", p.id)
            .put("created", p.createdMs)
            .put("easyWeek", p.easyWeek)
            .put("start", c.startDate.toString())
            .put("end", c.endDate.toString())
            .put("goal", c.goal.name)
            .put("theme", c.theme.name)
            .put("sports", JSONArray(c.sports.map { it.name }))
            .put("days", c.daysPerWeek)
            .put("free", JSONArray(c.freeDays.map { it.value }))
            .put("longDay", c.longDay.value)
            .put("target", c.targetType.name)
            .put("weeklyMin", c.weeklyMinutes)
            .put("monthTarget", c.monthTarget ?: JSONObject.NULL)
            .put("paces", JSONObject().apply { c.paces.forEach { (k, v) -> put(k.name, v) } })
            .put("index", c.monthIndex)
            .put("seed", c.seed)
            .put("prevMin", c.prevTotalMin ?: JSONObject.NULL)
            // The days are saved as planned, so a later app update never reshuffles a month in progress.
            .put("plan", JSONArray().apply { p.days.forEach { put(monthDayToJson(it)) } })
            .put("originals", JSONArray().apply { p.originals.values.forEach { put(monthDayToJson(it)) } })
            .put("dismissed", JSONArray(p.dismissed.map { it.toString() }))
    }

    private fun monthDayToJson(d: MonthDay) = JSONObject()
        .put("date", d.date.toString())
        .put("sport", d.sport?.name ?: JSONObject.NULL)
        .put("kind", d.kind.name)
        .put("min", d.minutes)
        .put("m", d.distanceM)
        .put("title", d.title)
        .put("detail", d.detail)
        .put("week", d.week)
        .put("edited", d.edited)
        .put("catchUp", d.catchUpOf?.toString() ?: JSONObject.NULL)

    private fun monthDayFromJson(d: JSONObject): MonthDay? = runCatching {
        MonthDay(
            date = LocalDate.parse(d.getString("date")),
            sport = if (d.isNull("sport")) null else runCatching { MonthSport.valueOf(d.getString("sport")) }.getOrNull(),
            kind = runCatching { MonthKind.valueOf(d.getString("kind")) }.getOrDefault(MonthKind.REST),
            minutes = d.optInt("min", 0).coerceAtLeast(0),
            distanceM = d.optDouble("m", 0.0).takeIf { !it.isNaN() && it >= 0 } ?: 0.0,
            title = d.optString("title", "Session"),
            detail = d.optString("detail", ""),
            week = d.optInt("week", 0).coerceAtLeast(0),
            edited = d.optBoolean("edited", false),
            catchUpOf = if (d.isNull("catchUp")) null else runCatching { LocalDate.parse(d.getString("catchUp")) }.getOrNull(),
        ).let { if (it.sport == null && it.kind != MonthKind.REST) it.copy(kind = MonthKind.REST, title = "Rest") else it }
    }.getOrNull()

    private fun monthlyFromJson(o: JSONObject): MonthlyPlan {
        val sports = o.getJSONArray("sports").let { a -> List(a.length()) { a.getString(it) } }
            .mapNotNull { runCatching { MonthSport.valueOf(it) }.getOrNull() }
            .ifEmpty { listOf(MonthSport.WALK) }
        val free = o.optJSONArray("free")?.let { a -> List(a.length()) { a.getInt(it) } }
            ?.filter { it in 1..7 }?.map { DayOfWeek.of(it) }?.toSet().orEmpty()
        val paces = o.optJSONObject("paces")?.let { p ->
            MonthSport.entries.filter { p.has(it.name) }.associateWith { p.getDouble(it.name) }
        }.orEmpty()
        val goal = runCatching { MonthGoal.valueOf(o.getString("goal")) }.getOrDefault(MonthGoal.FITTER)
        val config = MonthConfig(
            startDate = LocalDate.parse(o.getString("start")),
            endDate = LocalDate.parse(o.getString("end")),
            goal = goal,
            theme = runCatching { MonthTheme.valueOf(o.getString("theme")) }.getOrDefault(MonthTheme.BASE),
            sports = sports,
            daysPerWeek = o.getInt("days").coerceIn(1, 6),
            freeDays = free,
            longDay = DayOfWeek.of(o.optInt("longDay", 7).coerceIn(1, 7)),
            targetType = runCatching { TargetType.valueOf(o.getString("target")) }.getOrDefault(TargetType.DISTANCE),
            weeklyMinutes = o.getDouble("weeklyMin"),
            monthTarget = if (o.isNull("monthTarget")) null else o.optDouble("monthTarget").takeIf { !it.isNaN() && it > 0 },
            paces = paces,
            monthIndex = o.optInt("index", 0),
            seed = o.optLong("seed", 0L),
            prevTotalMin = if (o.isNull("prevMin")) null else o.optDouble("prevMin").takeIf { !it.isNaN() && it > 0 },
        )
        val id = o.getString("id")
        val created = o.optLong("created", 0L)
        val saved = o.optJSONArray("plan")?.let { a ->
            List(a.length()) { i -> monthDayFromJson(a.getJSONObject(i)) }.filterNotNull().distinctBy { it.date }.sortedBy { it.date }
        }
        // Missing or damaged days: rebuild from the settings.
        if (saved.isNullOrEmpty()) return MonthlyPlanner.generate(config, id, created)
        val dates = saved.map { it.date }.toSet()
        val originals = o.optJSONArray("originals")?.let { a ->
            List(a.length()) { i -> monthDayFromJson(a.getJSONObject(i)) }.filterNotNull()
        }.orEmpty().filter { it.date in dates }.associateBy { it.date }
        // An "edited" day with no original to go back to is just a normal day.
        val days = saved.map { if (it.edited && it.date !in originals) it.copy(edited = false) else it }
        val dismissed = o.optJSONArray("dismissed")?.let { a ->
            List(a.length()) { i -> runCatching { LocalDate.parse(a.getString(i)) }.getOrNull() }
        }.orEmpty().filterNotNull().toSet()
        return MonthlyPlan(id, config, days, created, o.optInt("easyWeek", -1), originals, dismissed)
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
            val target = File(dir, name)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write $name", e)
        }
    }

    private fun changed() {
        onChanged?.invoke()
    }
}
