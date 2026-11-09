package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.Target
import com.attention.domain.TargetMove
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 目标树管理工具(P1.1 #241):一个意图级变更工具,10 个 action 覆盖创建、重命名、
 * 排序、展开折叠、立即换父、未来移动的安排/修改/取消、归档与恢复;全部 action
 * 内部映射 [AttentionCommand] 的目标管理 command,对既有引擎纯函数零复制。
 *
 * 风险分级静态映射(#226 分级表):日常整理(create/rename/reorder/set_expanded)
 * 为 [RiskLevel.Auto];结构与口径变更(move/schedule_move/update_move/cancel_move/
 * archive/restore)为 [RiskLevel.Confirmation]。action 全集不含任何删除类动作
 * (永禁红线):Forbidden 不进 [riskLevels] 声明,目录的注册拒绝与清单断言共同钉住。
 *
 * 解析相([ChangeTool.command])先做参数形状校验(缺字段、非法枚举、日期格式),
 * 再以 [WorkspaceSource] 快照做存在性预校验:未知目标/移动 id 在派发之前就抛出,
 * 由循环折算为错误观测回填,推理器可自行纠正——自动级免去无效派发,确认级不铸令牌。
 * 领域规则(防移入后代、生效日界线、级联恢复)不在此复制,仍由引擎执行期把守;
 * 快照到执行之间的 TOCTOU 窗口按 P0 契约收敛为失败终态,轨迹保留可诊断。
 *
 * 观测相([ChangeTool.observation])对齐 `query_targets` 的扁平目标条目先例
 * (缺省字段省略、null 不写),只投影受影响目标及其涉父目标,不回显全量状态
 * (隐私最小化);未来移动类 action 附带回执移动本身——LLM 后续 update/cancel
 * 需要 moveId。观测条目不携带进度汇总:该相拿不到 now/zone,进度属查询工具的职责。
 */
class ManageTargetTool(private val source: WorkspaceSource) : ChangeTool {

    override val name = "manage_target"

    override val description = "管理目标树:创建、重命名、排序、展开折叠、换父移动、未来移动安排与取消、归档与恢复"

    /** action 判别的简版 object schema,字段描述供 P3 序列化进 prompt。 */
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(FIELD_ACTION) {
                put("type", "string")
                put("description", "目标管理动作:$ACTION_CHOICES")
            }
            putJsonObject(FIELD_TITLE) {
                put("type", "string")
                put("description", "目标名称;create 与 rename 必填")
            }
            putJsonObject(FIELD_PARENT_ID) {
                put("type", "string")
                put("description", "父目标 id;create 与 schedule_move 可选,缺省为根目标;update_move 缺省表示移到根目标")
            }
            putJsonObject(FIELD_TARGET_ID) {
                put("type", "string")
                put("description", "目标 id;rename/reorder/set_expanded/move/archive/schedule_move/cancel_move 必填")
            }
            putJsonObject(FIELD_TARGET_IDS) {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "目标 id 列表;restore 必填,可一次恢复多个")
            }
            putJsonObject(FIELD_NEW_INDEX) {
                put("type", "integer")
                put("description", "同层新位置(从 0 起);reorder 必填")
            }
            putJsonObject(FIELD_EXPANDED) {
                put("type", "boolean")
                put("description", "true 展开 / false 折叠;set_expanded 必填")
            }
            putJsonObject(FIELD_NEW_PARENT_ID) {
                put("type", "string")
                put("description", "新父目标 id;move 必填,缺省表示移到根目标")
            }
            putJsonObject(FIELD_EFFECTIVE_FROM) {
                put("type", "string")
                put("description", "未来移动生效日 YYYY-MM-DD,必须晚于当前规划日;schedule_move 与 update_move 必填")
            }
            putJsonObject(FIELD_MOVE_ID) {
                put("type", "string")
                put("description", "未来移动 id(schedule_move 的观测会回执);update_move 与 cancel_move 必填")
            }
        }
        putJsonArray("required") { add(JsonPrimitive(FIELD_ACTION)) }
    }

    /** 声明与映射同源:[ACTION_RISK] 的值域即声明范围,Forbidden 天然不在其中。 */
    override val riskLevels = ACTION_RISK.values.toSet()

    /** 按 action 静态定级;未知 action 与缺 action 同为解析失败,零派发。 */
    override fun riskLevel(args: JsonObject): RiskLevel = ACTION_RISK.getValue(action(args))

    /**
     * 解析相:形状校验 + 快照存在性预校验。快照只在解析开始时取一次,
     * 一次调用内的预校验基于同一份一致视图。
     */
    override fun command(args: JsonObject): AttentionCommand {
        val snapshot = source.snapshot()
        return when (val action = action(args)) {
            ACTION_CREATE -> AttentionCommand.CreateTarget(
                title = requiredString(args, FIELD_TITLE),
                parentId = snapshot.optionalTarget(args, FIELD_PARENT_ID),
            )
            ACTION_RENAME -> AttentionCommand.RenameTarget(snapshot.requiredTarget(args, FIELD_TARGET_ID), requiredString(args, FIELD_TITLE))
            ACTION_REORDER -> AttentionCommand.ReorderTarget(snapshot.requiredTarget(args, FIELD_TARGET_ID), requiredInt(args, FIELD_NEW_INDEX))
            ACTION_SET_EXPANDED -> AttentionCommand.SetTargetExpanded(
                targetId = snapshot.requiredTarget(args, FIELD_TARGET_ID),
                expanded = requiredBoolean(args, FIELD_EXPANDED),
            )
            ACTION_MOVE -> AttentionCommand.MoveTarget(
                targetId = snapshot.requiredTarget(args, FIELD_TARGET_ID),
                newParentId = snapshot.optionalTarget(args, FIELD_NEW_PARENT_ID),
            )
            ACTION_SCHEDULE_MOVE -> AttentionCommand.ScheduleTargetMove(
                targetId = snapshot.requiredTarget(args, FIELD_TARGET_ID),
                parentId = snapshot.optionalTarget(args, FIELD_PARENT_ID),
                effectiveFrom = requiredDay(args),
            )
            ACTION_UPDATE_MOVE -> {
                snapshot.pendingMove(args)
                AttentionCommand.UpdateTargetMove(
                    moveId = requiredString(args, FIELD_MOVE_ID),
                    parentId = snapshot.optionalTarget(args, FIELD_PARENT_ID),
                    effectiveFrom = requiredDay(args),
                )
            }
            ACTION_CANCEL_MOVE -> snapshot.cancelMoveCommand(args)
            ACTION_ARCHIVE -> AttentionCommand.ArchiveTarget(snapshot.requiredTarget(args, FIELD_TARGET_ID))
            ACTION_RESTORE -> AttentionCommand.RestoreTargets(snapshot.requiredTargets(args))
            else -> throw IllegalArgumentException("action 必须是 $ACTION_CHOICES")
        }
    }

    /** 观测相:受影响目标及涉父目标的扁平条目,未来移动类附带回执。 */
    override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject {
        val action = action(args)
        val state = result.state
        return buildJsonObject {
            putJsonArray("targets") { affectedTargets(action, args, state).forEach { add(targetEntry(it)) } }
            when (action) {
                ACTION_SCHEDULE_MOVE, ACTION_UPDATE_MOVE -> renderedMove(action, args, state)?.let { put("targetMove", moveEntry(it)) }
                ACTION_CANCEL_MOVE -> optionalString(args, FIELD_MOVE_ID)?.let { put("cancelledMoveId", it) }
            }
        }
    }

    /** 受影响目标与涉父目标:条目集合按 action 显式圈定,去重保序,不回显全量状态。 */
    private fun affectedTargets(action: String, args: JsonObject, state: AttentionState): List<Target> {
        val targets = when (action) {
            // addTarget 恒把新建目标追加在末尾,新建者即最后一个
            ACTION_CREATE -> listOfNotNull(state.targets.lastOrNull())
            ACTION_RESTORE -> optionalStringList(args, FIELD_TARGET_IDS).orEmpty().mapNotNull { state.targetById(it) }
            ACTION_UPDATE_MOVE -> listOfNotNull(renderedMove(action, args, state)?.let { state.targetById(it.targetId) })
            else -> listOfNotNull(optionalString(args, FIELD_TARGET_ID)?.let { state.targetById(it) })
        }
        val parentId = when (action) {
            ACTION_MOVE -> optionalString(args, FIELD_NEW_PARENT_ID)
            ACTION_CREATE, ACTION_SCHEDULE_MOVE -> optionalString(args, FIELD_PARENT_ID)
            // 其余 action 的涉父目标即条目自身的当前父
            else -> targets.firstOrNull()?.parentId
        }
        return (targets + parentId?.let { state.targetById(it) }.let(::listOfNotNull)).distinctBy { it.id }
    }

    /** 观测里回执的未来移动:schedule 取新安排者(领域恒追加末尾),update 按保留的 id 取。 */
    private fun renderedMove(action: String, args: JsonObject, state: AttentionState): TargetMove? = when (action) {
        ACTION_SCHEDULE_MOVE -> state.targetMoves.lastOrNull()?.takeIf { it.targetId == optionalString(args, FIELD_TARGET_ID) }
        else -> optionalString(args, FIELD_MOVE_ID)?.let { id -> state.targetMoves.firstOrNull { it.id == id } }
    }

    private fun targetEntry(target: Target): JsonObject = buildJsonObject {
        put("id", target.id)
        put("title", target.title)
        target.parentId?.let { put("parentId", it) }
        put("archived", target.archived)
    }

    private fun moveEntry(move: TargetMove): JsonObject = buildJsonObject {
        put("id", move.id)
        put("targetId", move.targetId)
        move.parentId?.let { put("parentId", it) }
        put("effectiveFrom", move.effectiveFrom)
    }

    private fun action(args: JsonObject): String {
        val raw = optionalString(args, FIELD_ACTION) ?: throw IllegalArgumentException("$FIELD_ACTION 必填:$ACTION_CHOICES")
        require(raw in ACTION_RISK) { "action 必须是 $ACTION_CHOICES,实际「$raw」" }
        return raw
    }

    // ---- 参数形状校验(解析相第一段)----

    private fun optionalString(args: JsonObject, field: String): String? =
        (args[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun requiredString(args: JsonObject, field: String): String =
        optionalString(args, field) ?: throw IllegalArgumentException("$field 必填")

    private fun requiredInt(args: JsonObject, field: String): Int =
        optionalString(args, field)?.toIntOrNull() ?: throw IllegalArgumentException("$field 必须是整数")

    private fun requiredBoolean(args: JsonObject, field: String): Boolean =
        (args[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.booleanOrNull
            ?: throw IllegalArgumentException("$field 必须是 true 或 false")

    private fun requiredDay(args: JsonObject): String {
        val raw = requiredString(args, FIELD_EFFECTIVE_FROM)
        try {
            LocalDate.parse(raw)
        } catch (invalid: DateTimeParseException) {
            throw IllegalArgumentException("$FIELD_EFFECTIVE_FROM 需要 YYYY-MM-DD 格式,实际「$raw」", invalid)
        }
        return raw
    }

    private fun optionalStringList(args: JsonObject, field: String): List<String>? {
        val element = args[field] ?: return null
        if (element is JsonNull) return null
        require(element is JsonArray) { "$field 必须是字符串数组" }
        return element.map { item ->
            require(item is JsonPrimitive && item !is JsonNull) { "$field 必须是字符串数组" }
            item.content
        }
    }

    // ---- 存在性预校验(解析相第二段,基于 WorkspaceSource 快照)----

    private fun WorkspaceView.requiredTarget(args: JsonObject, field: String): String =
        existingTarget(requiredString(args, field), field)

    private fun WorkspaceView.optionalTarget(args: JsonObject, field: String): String? =
        optionalString(args, field)?.let { existingTarget(it, field) }

    private fun WorkspaceView.existingTarget(id: String, field: String): String {
        require(state.targets.any { it.id == id }) { "目标不存在:$field=「$id」" }
        return id
    }

    private fun WorkspaceView.requiredTargets(args: JsonObject): Set<String> {
        val ids = optionalStringList(args, FIELD_TARGET_IDS)
        require(!ids.isNullOrEmpty()) { "$FIELD_TARGET_IDS 必填且不能为空" }
        ids.forEach { existingTarget(it, FIELD_TARGET_IDS) }
        return ids.toSet()
    }

    private fun WorkspaceView.pendingMove(args: JsonObject): TargetMove {
        val moveId = requiredString(args, FIELD_MOVE_ID)
        return state.targetMoves.firstOrNull { it.id == moveId }
            ?: throw IllegalArgumentException("未来目标移动不存在:moveId=「$moveId」")
    }

    /** cancel 的 args 同时携带 moveId 与 targetId:交叉校验归属一致,观测才能投影受影响目标。 */
    private fun WorkspaceView.cancelMoveCommand(args: JsonObject): AttentionCommand.CancelTargetMove {
        val move = pendingMove(args)
        val targetId = requiredTarget(args, FIELD_TARGET_ID)
        require(move.targetId == targetId) { "移动 ${move.id} 不属于目标「$targetId」" }
        return AttentionCommand.CancelTargetMove(move.id)
    }

    private fun AttentionState.targetById(id: String): Target? = targets.firstOrNull { it.id == id }

    companion object {
        /**
         * action → 风险级别的单一事实来源:schema 描述、[riskLevel] 判定与
         * 「无删除类动作」红线断言共用;键集恰为 10 个管理动作,Forbidden 不在其中。
         */
        internal val ACTION_RISK: Map<String, RiskLevel> = mapOf(
            ACTION_CREATE to RiskLevel.Auto,
            ACTION_RENAME to RiskLevel.Auto,
            ACTION_REORDER to RiskLevel.Auto,
            ACTION_SET_EXPANDED to RiskLevel.Auto,
            ACTION_MOVE to RiskLevel.Confirmation,
            ACTION_SCHEDULE_MOVE to RiskLevel.Confirmation,
            ACTION_UPDATE_MOVE to RiskLevel.Confirmation,
            ACTION_CANCEL_MOVE to RiskLevel.Confirmation,
            ACTION_ARCHIVE to RiskLevel.Confirmation,
            ACTION_RESTORE to RiskLevel.Confirmation,
        )

        private val ACTION_CHOICES = ACTION_RISK.keys.joinToString("/")

        private const val ACTION_CREATE = "create"
        private const val ACTION_RENAME = "rename"
        private const val ACTION_REORDER = "reorder"
        private const val ACTION_SET_EXPANDED = "set_expanded"
        private const val ACTION_MOVE = "move"
        private const val ACTION_SCHEDULE_MOVE = "schedule_move"
        private const val ACTION_UPDATE_MOVE = "update_move"
        private const val ACTION_CANCEL_MOVE = "cancel_move"
        private const val ACTION_ARCHIVE = "archive"
        private const val ACTION_RESTORE = "restore"

        private const val FIELD_ACTION = "action"
        private const val FIELD_TITLE = "title"
        private const val FIELD_PARENT_ID = "parentId"
        private const val FIELD_TARGET_ID = "targetId"
        private const val FIELD_TARGET_IDS = "targetIds"
        private const val FIELD_NEW_INDEX = "newIndex"
        private const val FIELD_EXPANDED = "expanded"
        private const val FIELD_NEW_PARENT_ID = "newParentId"
        private const val FIELD_EFFECTIVE_FROM = "effectiveFrom"
        private const val FIELD_MOVE_ID = "moveId"
    }
}
