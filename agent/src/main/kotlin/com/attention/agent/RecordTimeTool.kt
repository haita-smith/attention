package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.TimeEntry
import com.attention.domain.timerTargetLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 时间记录工具(P2.2 #246):一个意图级变更工具,4 个 action 覆盖账本记录的补记与
 * 归属、编辑与删除;全部 action 直接映射 [AttentionCommand] 既有 command,对领域零改动。
 *
 * 风险分级静态映射(#226 分级表):补记(backfill)与未归属归入计划(assign)是日常
 * 记账,自动执行、不被确认流程拖慢;编辑与删除历史记录(edit/delete)改写账本事实,
 * 未经用户确认不得派发。动作全集不含恢复删除(RestoreTimeEntry 为 UI 撤销专用)与
 * 任何 Forbidden 级操作——Forbidden 不进 [riskLevels] 声明,目录的注册拒绝与清单断言
 * 共同钉住。
 *
 * 解析相([ChangeTool.command])先做参数形状校验(日期格式、分钟数为正整数),再以
 * [WorkspaceSource] 快照做存在性预校验:目标与记录 id 必须存在,未知 id 在派发之前
 * 就转为错误观测回填,推理器可自行纠正——自动级免去无效派发,确认级不铸令牌。
 * 归属约束(只能归入未归属记录、目标未归档)属领域规则,不在此复制,仍由引擎执行期
 * 把守;快照到执行之间的 TOCTOU 窗口按 P0 契约收敛为失败终态,轨迹保留可诊断。
 *
 * 编辑的白名单由 schema 与 command 双重锁定:可改字段仅分钟数、目标与备注,args 与
 * schema 都没有日期字段——日期是账本事实,助手不能改(2026-11-09 裁决)。scheduleEntryId
 * 恒不暴露:日程链路本阶段排除。
 *
 * 观测相([ChangeTool.observation])投影为扁平记录条目(缺省字段省略、null 不写):
 * 补记与归属呈现新记账,编辑呈现改后记录,删除以被删记录回执呈现(状态里已查不到,
 * 从 [AttentionCommandResult.removedTimeEntries] 取);targetTitle 用引擎的
 * [timerTargetLabel],未归属兜底「未归属活动」。只含受影响记录,不回显全量账本
 * (隐私最小化)。
 */
class RecordTimeTool(private val source: WorkspaceSource) : ChangeTool {

    override val name = "record_time"

    override val description = "记录时间:补记一段时间(自动)、把未归属记录归入计划(自动)、编辑或删除历史记录(需确认)"

    /** 声明与映射同源:[ACTION_RISK] 的值域即声明范围,Forbidden 天然不在其中。 */
    override val riskLevels = ACTION_RISK.values.toSet()

    /** action 判别的简版 object schema,字段描述供 P3 序列化进 prompt。 */
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(FIELD_ACTION) {
                put("type", "string")
                put("description", "时间记录动作:$ACTION_CHOICES")
            }
            putJsonObject(FIELD_PLANNING_DATE) {
                put("type", "string")
                // 日期字段的唯一归属就是补记:这段描述是「编辑不可改日期」的 schema 锁定
                put("description", "补记的规划日 YYYY-MM-DD;仅 backfill 使用,历史记录的日期是账本事实,不可通过编辑修改")
            }
            putJsonObject(FIELD_DURATION_MINUTES) {
                put("type", "integer")
                put("description", "分钟数,正整数;backfill 与 edit 必填")
            }
            putJsonObject(FIELD_TARGET_ID) {
                put("type", "string")
                put("description", "目标 id;backfill 可选,缺省为未归属;assign 与 edit 必填")
            }
            putJsonObject(FIELD_NOTE) {
                put("type", "string")
                put("description", "可选备注;backfill 与 edit 使用")
            }
            putJsonObject(FIELD_ENTRY_ID) {
                put("type", "string")
                put("description", "时间记录 id;edit 与 delete 必填")
            }
            putJsonObject(FIELD_ENTRY_IDS) {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "未归属时间记录 id 列表;assign 必填,可一次归入多条")
            }
        }
        putJsonArray("required") { add(JsonPrimitive(FIELD_ACTION)) }
    }

    /** 按 action 静态定级;未知 action 与缺 action 同为解析失败,零派发。 */
    override fun riskLevel(args: JsonObject): RiskLevel = ACTION_RISK.getValue(action(args))

    /**
     * 解析相:形状校验 + 快照预校验。快照只在解析开始时取一次,
     * 一次调用内的预校验基于同一份一致视图。
     */
    override fun command(args: JsonObject): AttentionCommand {
        val snapshot = source.snapshot().state
        return when (action(args)) {
            ACTION_BACKFILL -> AttentionCommand.AddManualTimeEntry(
                planningDate = requiredDayArg(args, FIELD_PLANNING_DATE).toString(),
                durationMinutes = positiveMinutes(args),
                targetId = optionalStringArg(args, FIELD_TARGET_ID)?.let { snapshot.existingTargetId(it) },
                note = optionalStringArg(args, FIELD_NOTE) ?: "",
            )
            ACTION_ASSIGN -> AttentionCommand.AssignUnowned(
                entryIds = snapshot.existingEntryIds(args),
                targetId = snapshot.existingTargetId(requiredStringArg(args, FIELD_TARGET_ID)),
            )
            ACTION_EDIT -> AttentionCommand.EditTimeEntry(
                entryId = snapshot.existingEntryId(requiredStringArg(args, FIELD_ENTRY_ID)),
                durationMinutes = positiveMinutes(args),
                targetId = snapshot.existingTargetId(requiredStringArg(args, FIELD_TARGET_ID)),
                note = optionalStringArg(args, FIELD_NOTE) ?: "",
            )
            ACTION_DELETE -> AttentionCommand.DeleteTimeEntry(
                snapshot.existingEntryId(requiredStringArg(args, FIELD_ENTRY_ID)),
            )
            else -> throw IllegalArgumentException("action 必须是 $ACTION_CHOICES")
        }
    }

    /** 观测相:补记/归属/编辑投影受影响记录,删除以被删记录回执。 */
    override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject = buildJsonObject {
        if (action(args) == ACTION_DELETE) {
            putJsonArray(FIELD_REMOVED_ENTRIES) {
                result.removedTimeEntries.forEach { add(result.state.recordEntry(it)) }
            }
        } else {
            putJsonArray("entries") {
                result.timeEntries.forEach { add(result.state.recordEntry(it)) }
            }
        }
    }

    /**
     * 扁平记录条目投影(本地私有实现;与并行的 timer_control 各自持有,评审轮统一):
     * id/planningDate/durationMinutes/source 恒写,targetId 与备注缺省省略,
     * targetTitle 用引擎标签——与 query_timer 的口径同源,永不自造。
     */
    private fun AttentionState.recordEntry(entry: TimeEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("planningDate", entry.planningDate)
        put("durationMinutes", entry.durationMinutes)
        entry.targetId?.let { put("targetId", it) }
        put("targetTitle", timerTargetLabel(entry.targetId))
        put("source", entry.source.name)
        if (entry.note.isNotBlank()) put("note", entry.note)
    }

    private fun action(args: JsonObject): String {
        val raw = optionalStringArg(args, FIELD_ACTION) ?: throw IllegalArgumentException("$FIELD_ACTION 必填:$ACTION_CHOICES")
        require(raw in ACTION_RISK) { "action 必须是 $ACTION_CHOICES,实际「$raw」" }
        return raw
    }

    /** 分钟数为正整数;非整数与零值负值同为形状错误,在派发之前抛出。 */
    private fun positiveMinutes(args: JsonObject): Int {
        val raw = optionalStringArg(args, FIELD_DURATION_MINUTES) ?: throw IllegalArgumentException("$FIELD_DURATION_MINUTES 必填")
        val minutes = raw.toIntOrNull() ?: throw IllegalArgumentException("$FIELD_DURATION_MINUTES 必须是正整数,实际「$raw」")
        require(minutes > 0) { "$FIELD_DURATION_MINUTES 必须是正整数,实际 $minutes" }
        return minutes
    }

    // ---- 存在性预校验(解析相第二段,基于 WorkspaceSource 快照)----

    private fun AttentionState.existingTargetId(id: String): String {
        require(targets.any { it.id == id }) { "目标不存在:「$id」" }
        return id
    }

    private fun AttentionState.existingEntryId(id: String): String {
        require(timeEntries.any { it.id == id }) { "时间记录不存在:「$id」" }
        return id
    }

    private fun AttentionState.existingEntryIds(args: JsonObject): Set<String> {
        val element = args[FIELD_ENTRY_IDS] ?: throw IllegalArgumentException("$FIELD_ENTRY_IDS 必填且不能为空")
        if (element is JsonNull) throw IllegalArgumentException("$FIELD_ENTRY_IDS 必填且不能为空")
        require(element is JsonArray) { "$FIELD_ENTRY_IDS 必须是字符串数组" }
        val ids = element.map { item ->
            require(item is JsonPrimitive && item !is JsonNull) { "$FIELD_ENTRY_IDS 必须是字符串数组" }
            item.content
        }
        require(ids.isNotEmpty()) { "$FIELD_ENTRY_IDS 必填且不能为空" }
        ids.forEach { existingEntryId(it) }
        return ids.toSet()
    }

    companion object {
        /**
         * action → 风险级别的单一事实来源:schema 描述、[riskLevel] 判定与清单断言
         * 共用;键集恰为 4 个记录动作,Forbidden 不在其中。
         */
        internal val ACTION_RISK: Map<String, RiskLevel> = mapOf(
            ACTION_BACKFILL to RiskLevel.Auto,
            ACTION_ASSIGN to RiskLevel.Auto,
            ACTION_EDIT to RiskLevel.Confirmation,
            ACTION_DELETE to RiskLevel.Confirmation,
        )

        private val ACTION_CHOICES = ACTION_RISK.keys.joinToString("/")

        private const val ACTION_BACKFILL = "backfill"
        private const val ACTION_ASSIGN = "assign"
        private const val ACTION_EDIT = "edit"
        private const val ACTION_DELETE = "delete"

        private const val FIELD_ACTION = "action"
        private const val FIELD_PLANNING_DATE = "planningDate"
        private const val FIELD_DURATION_MINUTES = "durationMinutes"
        private const val FIELD_TARGET_ID = "targetId"
        private const val FIELD_NOTE = "note"
        private const val FIELD_ENTRY_ID = "entryId"
        private const val FIELD_ENTRY_IDS = "entryIds"
        private const val FIELD_REMOVED_ENTRIES = "removedTimeEntries"
    }
}
