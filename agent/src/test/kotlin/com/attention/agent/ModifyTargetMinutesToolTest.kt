package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.GoalCadence
import com.attention.domain.GoalStage
import com.attention.domain.Target
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * set_goal 的 modify_target_minutes action(#243)的工具契约:
 * 确认级分级、解析相位的领域 command 映射与存在性预校验(阶段存在、归属、未完成)、
 * 观测投影与其他 action 同一形状。循环级链路见 ModifyTargetMinutesFlowTest。
 */
class ModifyTargetMinutesToolTest {
    private val viewNow = Instant.parse("2026-10-08T12:00:00Z")
    private val viewZone = ZoneOffset.UTC

    /** 快照底座:一个带周阶段(目标 100,起点周一 2026-10-05)的目标。 */
    private fun baseState(stage: GoalStage = GoalStage("modify-stage", "goal-target", GoalCadence.WEEKLY, 100, "2026-10-05")) =
        AttentionState(targets = listOf(Target("goal-target", title = "阅读")), goalStages = listOf(stage))

    private fun tool(state: AttentionState = baseState()) =
        SetGoalTool(WorkspaceSource { WorkspaceView(state, viewNow, viewZone) })

    private fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("action", "modify_target_minutes")
        put("targetId", "goal-target")
        put("stageId", "modify-stage")
        put("targetMinutes", 50)
        apply(build)
    }

    @Test
    fun modify_target_minutes_is_a_confirmation_action_mapping_to_update_goal_stage() {
        val tool = tool()

        assertEquals(RiskLevel.Confirmation, tool.riskLevel(args()))

        val plain = tool.command(args())
        assertEquals(AttentionCommand.UpdateGoalStage(stageId = "modify-stage", targetMinutes = 50, dueDate = null), plain)

        val withDue = tool.command(args { put("targetMinutes", 60); put("dueDate", "2026-10-18") })
        assertEquals(AttentionCommand.UpdateGoalStage(stageId = "modify-stage", targetMinutes = 60, dueDate = "2026-10-18"), withDue)
    }

    @Test
    fun parse_phase_prevalidates_stage_existence_ownership_and_completion() {
        val completed = baseState(GoalStage("modify-stage", "goal-target", GoalCadence.WEEKLY, 100, "2026-10-05", completed = true))
        val otherTarget = baseState().copy(targets = listOf(Target("goal-target", title = "阅读"), Target("other-target", title = "另一目标")))
        val archived = baseState().copy(targets = listOf(Target("goal-target", title = "阅读", archived = true)))
        val cases = mapOf(
            "未知阶段" to (baseState() to args { put("stageId", "stranger") }),
            "阶段不属于目标" to (otherTarget to args { put("targetId", "other-target") }),
            "已完成阶段" to (completed to args()),
            "缺阶段 id" to (baseState() to args { put("stageId", "") }),
            "缺目标分钟" to (baseState() to buildJsonObject {
                put("action", "modify_target_minutes")
                put("targetId", "goal-target")
                put("stageId", "modify-stage")
            }),
            "非整数分钟" to (baseState() to args { put("targetMinutes", "many") }),
            "非正数分钟" to (baseState() to args { put("targetMinutes", 0) }),
            "归档目标" to (archived to args()),
        )
        cases.forEach { (label, pair) ->
            val (state, caseArgs) = pair
            val thrown = assertThrows("$label 应在解析相位失败", IllegalArgumentException::class.java) {
                tool(state).command(caseArgs)
            }
            assertTrue("$label 的报错应可指导纠正", thrown.message?.isNotBlank() == true)
        }
    }

    @Test
    fun observation_projects_the_affected_target_like_every_other_action() {
        val tool = tool()
        val executed = AttentionCommand.UpdateGoalStage(stageId = "modify-stage", targetMinutes = 50)
            .execute(baseState(), viewNow, viewZone)
        // 观测与 query_targets 同一口径:goalSummary 只汇总一次性阶段,周阶段在此读数为 0,
        // 形状(扁平目标条目 + 进度汇总 + count)与其他 action 完全一致
        val expected = buildJsonObject {
            putJsonArray("targets") {
                add(buildJsonObject {
                    put("id", "goal-target")
                    put("title", "阅读")
                    put("archived", false)
                    put("targetMinutes", 0)
                    put("actualMinutes", 0)
                    put("gapMinutes", 0)
                    put("completed", false)
                })
            }
            put("count", 1)
        }
        assertEquals(expected, tool.observation(args(), executed))
    }

    @Test
    fun the_modify_action_enters_the_tool_action_set_and_schema_description() {
        // 动作全集的单一事实来源含新 action:schema 描述、风险判定与解析共用
        assertTrue("modify_target_minutes 应进入动作全集", SetGoalTool.ACTIONS.contains("modify_target_minutes"))
        val schema = tool().parametersSchema
        val actionDescription = schema["properties"]!!
            .let { it as JsonObject }["action"]!!
            .let { it as JsonObject }["description"]!!
            .let { it as kotlinx.serialization.json.JsonPrimitive }.content
        assertTrue("action 描述应包含 modify_target_minutes", actionDescription.contains("modify_target_minutes"))
    }
}
