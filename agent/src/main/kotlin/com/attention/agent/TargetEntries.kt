package com.attention.agent

import com.attention.domain.AttentionState
import com.attention.domain.Target
import com.attention.domain.goalSummary
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 产出目标条目的工具共用的扁平目标条目投影(`query_targets` 先例):id/title、
 * 可省的 parentId、archived 与进度汇总四元(targetMinutes/actualMinutes/gapMinutes/
 * completed),缺省字段省略、null 不写。只读查询与两个变更工具的观测共用同一函数,
 * 进度口径不许各说各话;汇总以传入状态与规划日现算,不复制领域知识。
 */
internal fun AttentionState.targetEntry(target: Target, planningDay: LocalDate): JsonObject {
    val summary = goalSummary(target.id, planningDay)
    return buildJsonObject {
        put("id", target.id)
        put("title", target.title)
        target.parentId?.let { put("parentId", it) }
        put("archived", target.archived)
        put("targetMinutes", summary.targetMinutes)
        put("actualMinutes", summary.actualMinutes)
        put("gapMinutes", summary.gapMinutes)
        put("completed", summary.completed)
    }
}
