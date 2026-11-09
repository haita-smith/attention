package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.FutureGoalRule
import com.attention.domain.GoalCadence
import com.attention.domain.Migration
import com.attention.domain.Target
import com.attention.domain.goalSummary
import com.attention.domain.planningDate
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 目标设定工具:set_goal 一个意图、九个 action,按 action 静态分级。
 * 阶段追加(含一次性追加)是低风险整理,自动执行;修改已生效阶段的时间目标、
 * 未来规则与目标迁移的增改停决定「未来的目标承诺」,未经用户确认不得派发。
 * 动作全集不含任何删除/清空类 action(迁移与未生效规则的取消是标记或移除待生效
 * 意图,不是历史删除),也不声明 [RiskLevel.Forbidden]——永禁操作根本没有 command 通道。
 *
 * 解析相位以 [WorkspaceSource] 快照做存在性预校验:未知目标/阶段/规则/迁移 id、
 * 已完成阶段在解析时转为错误观测回填,推理器可自行纠正;确认级动作因此不会为
 * 无效请求铸造令牌。预校验只钉存在性、归属与完成态,领域规则(生效点边界、
 * 去重更新、一次性阶段拒绝)仍由 `:domain` 的 command 在执行期治理。
 *
 * 观测投影对齐 query_targets 先例:扁平目标条目 + 进度汇总,缺省字段不写,
 * 只含受影响目标,不回显全量状态。
 */
class SetGoalTool(private val source: WorkspaceSource) : ChangeTool {

    override val name = "set_goal"

    override val description = "设定目标:追加周期阶段或一次性阶段(自动);修改已生效阶段的时间目标、安排、修改或取消未来的目标规则与目标迁移(需确认)"

    override val riskLevels = setOf(RiskLevel.Auto, RiskLevel.Confirmation)

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "set_goal 的动作:$ACTION_CHOICES")
            }
            putJsonObject("targetId") { put("type", "string"); put("description", "目标 id") }
            putJsonObject("cadence") {
                put("type", "string")
                put("description", "周期类型:$CADENCE_CHOICES")
            }
            putJsonObject("targetMinutes") {
                put("type", "integer"); put("description", "阶段或未来规则的目标分钟数,正整数")
            }
            putJsonObject("startDate") { put("type", "string"); put("description", "阶段开始日,YYYY-MM-DD") }
            putJsonObject("dueDate") { put("type", "string"); put("description", "可选,截止日,YYYY-MM-DD") }
            putJsonObject("stageId") {
                put("type", "string")
                put("description", "目标阶段 id:add_future_rule/update_future_rule 缺省为目标级规则;modify_target_minutes 必填,是被修改的阶段")
            }
            putJsonObject("effectiveFrom") {
                put("type", "string"); put("description", "未来规则生效日,必须晚于当前规划日,YYYY-MM-DD")
            }
            putJsonObject("ruleId") { put("type", "string"); put("description", "未来规则 id,update/cancel_future_rule 必填") }
            putJsonObject("sourceStageId") { put("type", "string"); put("description", "迁移来源阶段 id") }
            putJsonObject("minutes") { put("type", "integer"); put("description", "迁移分钟数,不超过来源阶段的剩余缺口") }
            putJsonObject("destinationStartDate") { put("type", "string"); put("description", "迁移去向开始日,YYYY-MM-DD") }
            putJsonObject("destinationEndDate") { put("type", "string"); put("description", "可选,迁移去向截止日,YYYY-MM-DD") }
            putJsonObject("migrationId") { put("type", "string"); put("description", "迁移记录 id,update/cancel_migration 必填") }
        }
        // 必填契约写进 schema:所有 action 都要指明落在哪个目标上(取消类动作
        // 也用它做归属校验与观测投影),漏标就会漏参
        putJsonArray("required") {
            add(JsonPrimitive("action"))
            add(JsonPrimitive("targetId"))
        }
    }

    override fun riskLevel(args: JsonObject): RiskLevel = when (knownAction(args)) {
        ACTION_ADD_STAGE, ACTION_APPEND_ONE_TIME_STAGE -> RiskLevel.Auto
        else -> RiskLevel.Confirmation
    }

    override fun command(args: JsonObject): AttentionCommand {
        val state = source.snapshot().state
        return when (knownAction(args)) {
            ACTION_ADD_STAGE -> parseAddStage(state, args)
            ACTION_APPEND_ONE_TIME_STAGE -> parseAppendOneTimeStage(state, args)
            ACTION_MODIFY_TARGET_MINUTES -> parseModifyTargetMinutes(state, args)
            ACTION_ADD_FUTURE_RULE -> parseAddFutureRule(state, args)
            ACTION_UPDATE_FUTURE_RULE -> parseUpdateFutureRule(state, args)
            ACTION_CANCEL_FUTURE_RULE -> parseCancelFutureRule(state, args)
            ACTION_ADD_MIGRATION -> parseAddMigration(state, args)
            ACTION_UPDATE_MIGRATION -> parseUpdateMigration(state, args)
            else -> parseCancelMigration(state, args)
        }
    }

    private fun parseAddStage(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireActiveTarget(state, string(args, KEY_TARGET_ID))
        return AttentionCommand.AddGoalStage(
            targetId = target.id,
            cadence = cadenceArg(args),
            targetMinutes = positiveInt(args, KEY_TARGET_MINUTES),
            startDate = day(args, KEY_START_DATE).toString(),
            dueDate = optionalDay(args, KEY_DUE_DATE)?.toString(),
        )
    }

    private fun parseAppendOneTimeStage(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireActiveTarget(state, string(args, KEY_TARGET_ID))
        return AttentionCommand.AppendOneTimeGoalStage(target.id, positiveInt(args, KEY_TARGET_MINUTES))
    }

    /**
     * 修改已生效阶段的时间目标:生效点、去重更新与一次性阶段拒绝由引擎治理,
     * 这里只预校验阶段存在、归属与未完成(工票钉定的解析相位守卫),让确认级
     * 动作不会为无效请求铸造令牌。
     */
    private fun parseModifyTargetMinutes(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireActiveTarget(state, string(args, KEY_TARGET_ID))
        val stageId = string(args, KEY_STAGE_ID)
        if (stageId.isBlank()) throw IllegalArgumentException("stageId 必填:modify_target_minutes 修改的是具体阶段")
        requireModifiableStage(state, target.id, stageId)
        return AttentionCommand.UpdateGoalStage(
            stageId = stageId,
            targetMinutes = positiveInt(args, KEY_TARGET_MINUTES),
            dueDate = optionalDay(args, KEY_DUE_DATE)?.toString(),
        )
    }

    private fun parseAddFutureRule(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireActiveTarget(state, string(args, KEY_TARGET_ID))
        val stageId = optionalStageId(args)
        requireStageOfTarget(state, target.id, stageId)
        return AttentionCommand.AddFutureGoalRule(
            FutureGoalRule(
                targetId = target.id,
                stageId = stageId,
                cadence = cadenceArg(args),
                targetMinutes = positiveInt(args, KEY_TARGET_MINUTES),
                effectiveFrom = day(args, KEY_EFFECTIVE_FROM).toString(),
                dueDate = optionalDay(args, KEY_DUE_DATE)?.toString(),
            ),
        )
    }

    private fun parseUpdateFutureRule(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireActiveTarget(state, string(args, KEY_TARGET_ID))
        val existing = requireRuleOfTarget(state, target.id, string(args, KEY_RULE_ID))
        val stageId = optionalStageId(args)
        requireStageOfTarget(state, target.id, stageId)
        // 从既有规则 copy:id 与 targetId 不可改,与引擎「目标和阶段不能修改」同口径
        return AttentionCommand.UpdateFutureGoalRule(
            existing.copy(
                stageId = stageId,
                cadence = cadenceArg(args),
                targetMinutes = positiveInt(args, KEY_TARGET_MINUTES),
                effectiveFrom = day(args, KEY_EFFECTIVE_FROM).toString(),
                dueDate = optionalDay(args, KEY_DUE_DATE)?.toString(),
            ),
        )
    }

    private fun parseCancelFutureRule(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireTarget(state, string(args, KEY_TARGET_ID))
        val existing = requireRuleOfTarget(state, target.id, string(args, KEY_RULE_ID))
        return AttentionCommand.CancelFutureGoalRule(existing.id)
    }

    private fun parseAddMigration(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireActiveTarget(state, string(args, KEY_TARGET_ID))
        val sourceStageId = string(args, KEY_SOURCE_STAGE_ID)
        requireStageOfTarget(state, target.id, sourceStageId)
        return AttentionCommand.AddMigration(
            Migration(
                targetId = target.id,
                sourceStageId = sourceStageId,
                minutes = positiveInt(args, KEY_MINUTES),
                destinationStartDate = day(args, KEY_DESTINATION_START).toString(),
                destinationEndDate = optionalDay(args, KEY_DESTINATION_END)?.toString(),
            ),
        )
    }

    private fun parseUpdateMigration(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireActiveTarget(state, string(args, KEY_TARGET_ID))
        val existing = requireMigrationOfTarget(state, target.id, string(args, KEY_MIGRATION_ID))
        val sourceStageId = string(args, KEY_SOURCE_STAGE_ID)
        requireStageOfTarget(state, target.id, sourceStageId)
        return AttentionCommand.UpdateMigration(
            existing.copy(
                sourceStageId = sourceStageId,
                minutes = positiveInt(args, KEY_MINUTES),
                destinationStartDate = day(args, KEY_DESTINATION_START).toString(),
                destinationEndDate = optionalDay(args, KEY_DESTINATION_END)?.toString(),
            ),
        )
    }

    private fun parseCancelMigration(state: AttentionState, args: JsonObject): AttentionCommand {
        val target = requireTarget(state, string(args, KEY_TARGET_ID))
        val existing = requireMigrationOfTarget(state, target.id, string(args, KEY_MIGRATION_ID))
        return AttentionCommand.CancelMigration(existing.id)
    }

    override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject {
        val targetId = string(args, KEY_TARGET_ID)
        val target = result.state.targets.firstOrNull { it.id == targetId }
            ?: throw IllegalStateException("观测投影找不到目标:$targetId")
        // 规划日取自快照的时钟事实(ADR-0005):与只读工具的进度口径同源
        val planningDay = source.snapshot().planningDay
        val summary = result.state.goalSummary(target.id, planningDay)
        return buildJsonObject {
            putJsonArray("targets") {
                add(buildJsonObject {
                    put("id", target.id)
                    put("title", target.title)
                    target.parentId?.let { put("parentId", it) }
                    put("archived", target.archived)
                    put("targetMinutes", summary.targetMinutes)
                    put("actualMinutes", summary.actualMinutes)
                    put("gapMinutes", summary.gapMinutes)
                    put("completed", summary.completed)
                })
            }
            put("count", 1)
        }
    }

    /** 按名判定 action;未知名字在第一相就抛,风险与解析都不再继续。 */
    private fun knownAction(args: JsonObject): String {
        val raw = args["action"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("action 必填:$ACTION_CHOICES")
        return ACTIONS.firstOrNull { it == raw }
            ?: throw IllegalArgumentException("action 必须是 $ACTION_CHOICES,实际「$raw」")
    }

    /** 目标必须存在;不校验归档(取消类动作对归档目标同样成立)。 */
    private fun requireTarget(state: AttentionState, targetId: String): Target =
        state.targets.firstOrNull { it.id == targetId }
            ?: throw IllegalArgumentException("目标不存在:$targetId")

    /** 产生新的目标侧承诺的动作只对未归档目标成立,与引擎校验同口径。 */
    private fun requireActiveTarget(state: AttentionState, targetId: String): Target {
        val target = requireTarget(state, targetId)
        if (target.archived) throw IllegalArgumentException("目标已归档,不能追加阶段或规则:$targetId")
        return target
    }

    /** 阶段 id 缺省(空白)即目标级;非空时必须存在且属于该目标。 */
    private fun requireStageOfTarget(state: AttentionState, targetId: String, stageId: String) {
        if (stageId.isBlank()) return
        val stage = state.goalStages.firstOrNull { it.id == stageId }
            ?: throw IllegalArgumentException("目标阶段不存在:$stageId")
        if (stage.targetId != targetId) throw IllegalArgumentException("目标阶段 $stageId 不属于目标 $targetId")
    }

    /** 修改类动作的预校验:阶段必须存在、属于该目标且未完成——历史承诺不可改写。 */
    private fun requireModifiableStage(state: AttentionState, targetId: String, stageId: String) {
        val stage = state.goalStages.firstOrNull { it.id == stageId }
            ?: throw IllegalArgumentException("目标阶段不存在:$stageId")
        if (stage.targetId != targetId) throw IllegalArgumentException("目标阶段 $stageId 不属于目标 $targetId")
        if (stage.completed) throw IllegalArgumentException("目标阶段已完成,不能修改:$stageId")
    }

    private fun requireRuleOfTarget(state: AttentionState, targetId: String, ruleId: String): FutureGoalRule {
        val rule = state.futureGoalRules.firstOrNull { it.id == ruleId }
            ?: throw IllegalArgumentException("未来目标规则不存在:$ruleId")
        if (rule.targetId != targetId) throw IllegalArgumentException("未来目标规则 $ruleId 不属于目标 $targetId")
        return rule
    }

    private fun requireMigrationOfTarget(state: AttentionState, targetId: String, migrationId: String): Migration {
        val migration = state.migrations.firstOrNull { it.id == migrationId }
            ?: throw IllegalArgumentException("迁移记录不存在:$migrationId")
        if (migration.targetId != targetId) throw IllegalArgumentException("迁移记录 $migrationId 不属于目标 $targetId")
        return migration
    }

    /** 读取必填字符串参数;缺失即抛,由循环转成错误观测。 */
    private fun string(args: JsonObject, key: String): String =
        args[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("$key 必填")

    /** 读取可选阶段 id:空白折叠为空串(目标级规则),与引擎的 stageId 语义一致。 */
    private fun optionalStageId(args: JsonObject): String =
        args["stageId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: ""

    private fun positiveInt(args: JsonObject, key: String): Int {
        val raw = args[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("$key 必填")
        val value = raw.toIntOrNull() ?: throw IllegalArgumentException("$key 必须是正整数,实际「$raw」")
        if (value <= 0) throw IllegalArgumentException("$key 必须是正整数,实际 $value")
        return value
    }

    private fun day(args: JsonObject, key: String): LocalDate = parseDay(key, requireRaw(args, key))

    private fun optionalDay(args: JsonObject, key: String): LocalDate? =
        args[key]?.jsonPrimitive?.content?.let { parseDay(key, it) }

    private fun requireRaw(args: JsonObject, key: String): String =
        args[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("$key 必填")

    private fun parseDay(key: String, raw: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (invalid: DateTimeParseException) {
        throw IllegalArgumentException("$key 需要 YYYY-MM-DD 格式,实际「$raw」", invalid)
    }

    private fun cadenceArg(args: JsonObject): GoalCadence {
        val raw = requireRaw(args, "cadence")
        return GoalCadence.entries.firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("cadence 必须是 $CADENCE_CHOICES,实际「$raw」")
    }

    companion object {
        const val ACTION_ADD_STAGE = "add_stage"
        const val ACTION_APPEND_ONE_TIME_STAGE = "append_one_time_stage"
        const val ACTION_MODIFY_TARGET_MINUTES = "modify_target_minutes"
        const val ACTION_ADD_FUTURE_RULE = "add_future_rule"
        const val ACTION_UPDATE_FUTURE_RULE = "update_future_rule"
        const val ACTION_CANCEL_FUTURE_RULE = "cancel_future_rule"
        const val ACTION_ADD_MIGRATION = "add_migration"
        const val ACTION_UPDATE_MIGRATION = "update_migration"
        const val ACTION_CANCEL_MIGRATION = "cancel_migration"

        /** 动作全集的单一事实来源:schema 描述、风险判定与解析共用,顺序即声明顺序。 */
        val ACTIONS = listOf(
            ACTION_ADD_STAGE,
            ACTION_APPEND_ONE_TIME_STAGE,
            ACTION_MODIFY_TARGET_MINUTES,
            ACTION_ADD_FUTURE_RULE,
            ACTION_UPDATE_FUTURE_RULE,
            ACTION_CANCEL_FUTURE_RULE,
            ACTION_ADD_MIGRATION,
            ACTION_UPDATE_MIGRATION,
            ACTION_CANCEL_MIGRATION,
        )

        val ACTION_CHOICES = ACTIONS.joinToString("/")

        /** 周期取值的单一事实来源:与只读工具的 cadence 描述同一套口径。 */
        val CADENCE_CHOICES = GoalCadence.entries.joinToString("/") { it.name }

        private const val KEY_TARGET_ID = "targetId"
        private const val KEY_TARGET_MINUTES = "targetMinutes"
        private const val KEY_STAGE_ID = "stageId"
        private const val KEY_START_DATE = "startDate"
        private const val KEY_DUE_DATE = "dueDate"
        private const val KEY_EFFECTIVE_FROM = "effectiveFrom"
        private const val KEY_RULE_ID = "ruleId"
        private const val KEY_SOURCE_STAGE_ID = "sourceStageId"
        private const val KEY_MINUTES = "minutes"
        private const val KEY_DESTINATION_START = "destinationStartDate"
        private const val KEY_DESTINATION_END = "destinationEndDate"
        private const val KEY_MIGRATION_ID = "migrationId"
    }
}
