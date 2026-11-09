package com.attention.agent

import com.attention.domain.AttentionState
import com.attention.domain.TimeEntry
import com.attention.domain.timerTargetLabel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 产出时间记录条目的工具共用的扁平记录条目投影(`query_timer`/`timer_control`/
 * `record_time` 同源):id/planningDate/durationMinutes/source 恒写,targetId 与备注
 * 缺省省略、null 不写,targetTitle 用引擎的 [timerTargetLabel] 兜底「未归属活动」,
 * 永不自造。只读查询与计时/记录两个变更工具的观测共用同一函数,条目口径不许
 * 各说各话;scheduleEntryId 不暴露——日程确认与补记链路本阶段排除(红线)。
 */
internal fun AttentionState.timeEntryEntry(entry: TimeEntry): JsonObject = buildJsonObject {
    put("id", entry.id)
    put("planningDate", entry.planningDate)
    put("durationMinutes", entry.durationMinutes)
    entry.targetId?.let { put("targetId", it) }
    put("targetTitle", timerTargetLabel(entry.targetId))
    put("source", entry.source.name)
    if (entry.note.isNotBlank()) put("note", entry.note)
}
