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

/**
 * 目标 id 存在性预校验的共用入口(解析相第二段,基于 [WorkspaceSource] 快照):
 * id 必须在快照的目标树中存在,未知 id 在派发之前就转为解析失败,由循环折算成
 * 错误观测回填——自动级免去无效派发,确认级不铸令牌。归档目标的存在性在此放行,
 * 拒绝属领域规则,仍由引擎执行期把守;快照到执行之间的 TOCTOU 窗口按 P0 契约
 * 收敛为失败终态。三个变更工具(manage_target/timer_control/record_time)共用
 * 同一份报错文案,不各说各话。
 */
internal fun WorkspaceView.requireTargetExists(id: String, field: String): String {
    require(state.targets.any { it.id == id }) { "目标不存在:$field=「$id」" }
    return id
}
