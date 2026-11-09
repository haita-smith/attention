package com.attention.agent

import com.attention.domain.ActiveTimer
import com.attention.domain.AttentionState
import com.attention.domain.ScheduleEntry
import com.attention.domain.agendaTimeline
import com.attention.domain.capacitySummary
import com.attention.domain.elapsedMillis
import com.attention.domain.periodStats
import com.attention.domain.planningDate
import com.attention.domain.timingScheduleEntryId
import com.attention.domain.timerTargetLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具看到的快照：领域状态加显式注入的 now 与 zone。
 * 所有投影都是这两个值的纯函数，与仓库既有约定一致（ADR-0005）：
 * 测试固定 now/zone，组合根提供真实来源。
 */
data class WorkspaceView(val state: AttentionState, val now: Instant, val zone: ZoneId) {
    /** [now] 按状态的规划日界线折算出的规划日，即工具语境下的「今天」。 */
    val planningDay: LocalDate
        get() = state.planningDate(now, zone)
}

/** 快照提供者：组合根在每次工具执行时取最新状态；循环与工具自身不持有状态。 */
fun interface WorkspaceSource {
    fun snapshot(): WorkspaceView
}

/**
 * 首批只读工具：目标树、日程时间轴、周期统计、计时状态。
 * 每个工具都是 [AttentionState] 的纯投影——这里只做读取与组装，不新增领域知识，
 * 领域规则全部仍在 `:domain` 的 read-model 函数里。工具不修改任何状态；
 * 变更类动作属于后续阶段（需要确认机制与危险操作基线），不从这里扩展。
 * 参数解析助手（日期/周期/必填串，见 ToolArgs.kt）与目标条目投影（targetEntry）
 * 与变更工具共用同一份实现，口径不分家。
 */
class WorkspaceTools(private val source: WorkspaceSource) {

    fun all(): List<AgentTool> = listOf(targetsTool(), agendaTool(), periodStatsTool(), timerTool())

    private fun targetsTool() = object : AgentTool {
        override val name = "query_targets"
        override val description = "查询目标树：每个目标的层级、归档状态与一次性目标的进度汇总"
        override val parametersSchema = buildJsonObject { put("type", "object") }

        override fun execute(args: JsonObject): JsonObject {
            val view = source.snapshot()
            return buildJsonObject {
                putJsonArray("targets") {
                    view.state.targets.forEach { add(view.state.targetEntry(it, view.planningDay)) }
                }
                put("count", view.state.targets.size)
            }
        }
    }

    private fun agendaTool() = object : AgentTool {
        override val name = "query_agenda"
        override val description = "查询某个规划日的日程：时间轴、未安排时间分组与每日容量占用"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("planningDate") {
                    put("type", "string")
                    put("description", "规划日，YYYY-MM-DD；缺省为今天")
                }
            }
        }

        override fun execute(args: JsonObject): JsonObject {
            val view = source.snapshot()
            val day = optionalDayArg(args, "planningDate") ?: view.planningDay
            val timeline = view.state.agendaTimeline(day)
            val capacity = view.state.capacitySummary(day.toString())
            return buildJsonObject {
                put("planningDate", day.toString())
                putJsonArray("timed") { timeline.timed.forEach { add(entryJson(view, it)) } }
                putJsonArray("unscheduled") { timeline.unscheduled.forEach { add(entryJson(view, it)) } }
                putJsonObject("capacity") {
                    capacity.capacityMinutes?.let { put("capacityMinutes", it) }
                    put("usedMinutes", capacity.usedMinutes)
                    put("overloaded", capacity.overloaded)
                }
            }
        }
    }

    private fun periodStatsTool() = object : AgentTool {
        override val name = "query_period_stats"
        override val description = "查询某个周期（日/周/月/一次性）的投入统计：目标、实际、未归属、迁移与超额"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("cadence") {
                    put("type", "string")
                    put("description", "周期类型：$CADENCE_CHOICES")
                }
                putJsonObject("date") {
                    put("type", "string")
                    put("description", "周期内任意一天，YYYY-MM-DD；缺省为今天")
                }
            }
            // 必填契约写进 schema：下一阶段 LLM 按它生成参数，漏标就会漏参
            putJsonArray("required") { add(JsonPrimitive("cadence")) }
        }

        override fun execute(args: JsonObject): JsonObject {
            val view = source.snapshot()
            val cadence = cadenceArg(args)
            val stats = view.state.periodStats(optionalDayArg(args, "date") ?: view.planningDay, cadence)
            val titles = view.state.targets.associate { it.id to it.title }
            return buildJsonObject {
                put("cadence", cadence.name)
                put("rangeStart", stats.range.start.toString())
                put("rangeEnd", stats.range.endInclusive.toString())
                put("targetMinutes", stats.targetMinutes)
                put("actualMinutes", stats.actualMinutes)
                put("unownedMinutes", stats.unownedMinutes)
                put("migrationMinutes", stats.migrationMinutes)
                put("excessMinutes", stats.excessMinutes)
                putJsonArray("byTarget") {
                    (stats.byTargetMinutes.keys + stats.subtreeByTargetMinutes.keys).distinct().forEach { targetId ->
                        add(buildJsonObject {
                            put("targetId", targetId)
                            put("title", titles[targetId] ?: targetId)
                            put("directMinutes", stats.byTargetMinutes[targetId] ?: 0)
                            put("subtreeMinutes", stats.subtreeByTargetMinutes[targetId] ?: 0)
                        })
                    }
                }
            }
        }
    }

    private fun timerTool() = object : AgentTool {
        override val name = "query_timer"
        override val description = "查询当前计时状态：是否在计时、归属目标与已计时长"
        override val parametersSchema = buildJsonObject { put("type", "object") }

        override fun execute(args: JsonObject): JsonObject {
            val view = source.snapshot()
            val timer: ActiveTimer? = view.state.activeTimer
            return buildJsonObject {
                if (timer == null) {
                    put("running", false)
                } else {
                    put("running", true)
                    put("paused", timer.paused)
                    put("targetTitle", view.state.timerTargetLabel(timer.targetId))
                    timer.targetId?.let { put("targetId", it) }
                    timer.scheduleEntryId?.let { put("scheduleEntryId", it) }
                    put("elapsedMillis", timer.elapsedMillis(view.now))
                }
            }
        }
    }

    private fun entryJson(view: WorkspaceView, entry: ScheduleEntry) = buildJsonObject {
        put("id", entry.id)
        put("title", entry.title)
        entry.startMinute?.let { put("startMinute", it) }
        entry.endMinute?.let { put("endMinute", it) }
        entry.estimatedMinutes?.let { put("estimatedMinutes", it) }
        entry.targetId?.let { put("targetId", it) }
        put("completed", entry.completed)
        if (view.state.timingScheduleEntryId() == entry.id) put("running", true)
    }
}
