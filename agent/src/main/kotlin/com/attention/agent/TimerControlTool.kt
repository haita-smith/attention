package com.attention.agent

import com.attention.domain.ActiveTimer
import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.TimeEntry
import com.attention.domain.elapsedMillis
import com.attention.domain.timerTargetLabel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 计时控制工具(P2.1 #245):一个意图级变更工具,5 个 action 覆盖开始、暂停、恢复、
 * 切换与停止,全部映射 [AttentionCommand] 的计时 command,对既有引擎纯函数零复制。
 * 开始/暂停/恢复走 #245 新增的薄包装 command;停止/切换复用既有 StopTimer/SwitchTimer
 * (同目标幂等、无活动计时器即开始、切换自动结算前段的既有语义原样保留)。
 *
 * 风险分级遵循 #226:计时操作必须随口可得、不被确认流程拖慢,5 个 action 全部
 * [RiskLevel.Auto];动作全集不含开机恢复(recoverTimer 为内部恢复路径)与任何
 * 删除类动作,Forbidden 不进 [riskLevels] 声明。
 *
 * 解析相([ChangeTool.command])先做 action 判别,再以 [WorkspaceSource] 快照做
 * 目标 id 存在性预校验(未知 id 在派发之前就抛出,由循环折算为错误观测回填,推理器
 * 可自行纠正);归档目标开始/切换的拒绝属领域规则,不前置复制,仍由引擎执行期把守,
 * 快照到执行之间的 TOCTOU 窗口按 P0 契约收敛为失败终态。scheduleEntryId 恒不传
 * (null):日程确认与补记链路本阶段排除,计时工具不与日程发生关联。
 *
 * 观测相([ChangeTool.observation])对齐 `query_timer` 先例(running/paused/
 * targetTitle/targetId?/elapsedMillis,targetTitle 用引擎 [timerTargetLabel] 兜底
 * 「未归属活动」);stop/switch 追加 `entries` 数组与 `totalMinutes`——跨规划日拆分
 * 呈现为多条记录、每条独立 planningDate、按日期排序、不合并,总计分钟数供核对;
 * 无活动计时器时 stop 为幂等 no-op,观测 running:false 即「当前未计时」反馈。
 * 时钟事实(投影 elapsedMillis)取自快照,状态一律以执行结果现算,与 manage_target
 * 同口径。
 *
 * 平台效果边界:工具只提交领域 command 并返回执行后投影,不触发通知、前台服务等
 * 任何平台效果——那归 app 层 TimerEffects 在状态变更后处理(#226 裁决,接线归 #239);
 * 观测键集恰为计时状态与结算条目,无任何效果类字段。
 */
class TimerControlTool(private val source: WorkspaceSource) : ChangeTool {

    override val name = "timer_control"

    override val description = "控制计时:开始(可未归属)、暂停、恢复、切换目标与停止结算"

    /** action 判别的简版 object schema,字段描述供 P3 序列化进 prompt。 */
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(FIELD_ACTION) {
                put("type", "string")
                put("description", "计时控制动作:$ACTION_CHOICES")
            }
            putJsonObject(FIELD_TARGET_ID) {
                put("type", "string")
                put("description", "目标 id;start 与 switch 可选,缺省表示未归属计时;pause/resume/stop 不需要")
            }
        }
        putJsonArray("required") { add(JsonPrimitive(FIELD_ACTION)) }
    }

    /** 声明与映射同源:[ACTION_RISK] 的值域即声明范围,Forbidden 天然不在其中。 */
    override val riskLevels = ACTION_RISK.values.toSet()

    /** 按 action 静态定级;未知 action 与缺 action 同为解析失败,零派发。 */
    override fun riskLevel(args: JsonObject): RiskLevel = ACTION_RISK.getValue(actionArg(args, ACTION_RISK.keys))

    /**
     * 解析相:action 判别 + 快照预校验。快照只在解析开始时取一次,一次调用内的
     * 预校验基于同一份一致视图;停止与暂停/恢复无需目标参数,直接复用既有 command。
     */
    override fun command(args: JsonObject): AttentionCommand {
        val snapshot = source.snapshot()
        return when (actionArg(args, ACTION_RISK.keys)) {
            ACTION_START -> AttentionCommand.StartTimer(snapshot.optionalExistingTarget(args))
            ACTION_PAUSE -> AttentionCommand.PauseTimer
            ACTION_RESUME -> AttentionCommand.ResumeTimer
            ACTION_SWITCH -> AttentionCommand.SwitchTimer(snapshot.optionalExistingTarget(args))
            ACTION_STOP -> AttentionCommand.StopTimer
            else -> throw IllegalArgumentException("action 必须是 $ACTION_CHOICES")
        }
    }

    /** 观测相:计时态对齐 query_timer;stop/switch 追加按规划日排序的结算条目与总分钟。 */
    override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject {
        val settled = settledEntries(actionArg(args, ACTION_RISK.keys), result)
        val state = result.state
        // 时钟事实取快照(ADR-0005):elapsed 与只读查询同源;状态一律用执行结果现算
        val now = source.snapshot().now
        return buildJsonObject {
            val timer: ActiveTimer? = state.activeTimer
            if (timer == null) {
                put("running", false)
            } else {
                put("running", true)
                put("paused", timer.paused)
                put("targetTitle", state.timerTargetLabel(timer.targetId))
                timer.targetId?.let { put("targetId", it) }
                put("elapsedMillis", timer.elapsedMillis(now))
            }
            if (settled.isNotEmpty()) {
                putJsonArray("entries") {
                    settled.sortedBy { it.planningDate }.forEach { add(state.timeEntryEntry(it)) }
                }
                put("totalMinutes", settled.sumOf { it.durationMinutes })
            }
        }
    }

    /** 只有 stop/switch 产生结算条目;其余 action 零结算,观测不追加 entries/totalMinutes。 */
    private fun settledEntries(action: String, result: AttentionCommandResult): List<TimeEntry> = when (action) {
        ACTION_STOP, ACTION_SWITCH -> result.timeEntries
        else -> emptyList()
    }

    /**
     * start/switch 的可空目标 id:缺省即未归属计时;给了 id 就必须在快照中存在
     * (归档目标的存在性放行,拒绝留引擎执行期,领域规则不前置复制)。
     */
    private fun WorkspaceView.optionalExistingTarget(args: JsonObject): String? =
        optionalStringArg(args, FIELD_TARGET_ID)?.let { requireTargetExists(it, FIELD_TARGET_ID) }

    companion object {
        /**
         * action → 风险级别的单一事实来源:schema 描述、[riskLevel] 判定与清单断言共用;
         * 键集恰为 5 个计时动作,全部即时([RiskLevel.Auto]),Forbidden 不在其中。
         */
        internal val ACTION_RISK: Map<String, RiskLevel> = mapOf(
            ACTION_START to RiskLevel.Auto,
            ACTION_PAUSE to RiskLevel.Auto,
            ACTION_RESUME to RiskLevel.Auto,
            ACTION_SWITCH to RiskLevel.Auto,
            ACTION_STOP to RiskLevel.Auto,
        )

        private val ACTION_CHOICES = ACTION_RISK.keys.joinToString("/")

        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_SWITCH = "switch"
        private const val ACTION_STOP = "stop"

        private const val FIELD_ACTION = "action"
        private const val FIELD_TARGET_ID = "targetId"
    }
}
