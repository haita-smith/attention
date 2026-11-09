package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionState
import com.attention.domain.FutureGoalRule
import com.attention.domain.GoalCadence
import com.attention.domain.GoalStage
import com.attention.domain.Migration
import com.attention.domain.Target
import com.attention.domain.addTimeEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * set_goal 工具在最高接缝(ReActAgent.run)上的全链路语义:
 * 自动级 action 恰好派发一次正确的 command、观测回填、循环继续至答复;
 * 确认级 action 请求即短路,确认后经同一执行通道恰好派发一次,拒绝则零派发;
 * 未知 id 在解析相位转为错误观测回填,零派发;降级开关关闭时确认级整体不可达。
 */
class SetGoalFlowTest {

    // 时钟与快照同一套事实(ADR-0005):上海时间 2026-03-06 10:00,规划日 2026-03-06
    private val clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) }

    private fun baseState() = AttentionState(
        targets = listOf(Target("flow-target", title = "阅读")),
        goalStages = listOf(GoalStage("flow-stage", "flow-target", GoalCadence.ONE_TIME, 30, "2026-03-01")),
    )

    /** 上一阶段已达成(投入 30 分钟)的底座,供追加一次性阶段的全链路用。 */
    private fun achievedState() = baseState().addTimeEntry("2026-03-01", 30, "flow-target")

    private fun sourceOf(state: AttentionState) = WorkspaceSource { WorkspaceView(state, DISPATCH_NOW, DISPATCH_ZONE) }

    private fun catalog(tool: ChangeTool, executor: CommandExecutor, gate: ConfirmationGate? = null) = ToolCatalog(
        changeTools = listOf(tool),
        executor = executor,
        clock = clock,
        gate = gate,
    )

    @Test
    fun auto_add_stage_runs_action_dispatch_observation_then_answer() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val tool = SetGoalTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "add_stage")
            put("targetId", "flow-target")
            put("cadence", "DAILY")
            put("targetMinutes", 30)
            put("startDate", "2026-03-06")
        }
        val expectedObservation = buildJsonObject {
            putJsonArray("targets") {
                add(buildJsonObject {
                    put("id", "flow-target")
                    put("title", "阅读")
                    put("archived", false)
                    put("targetMinutes", 30)
                    put("actualMinutes", 0)
                    put("gapMinutes", 30)
                    put("completed", false)
                })
            }
            put("count", 1)
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("给阅读加每日阶段", ActionRequest("set_goal", args)),
            ReasonerDecision.Answer(null, "已添加每日阶段"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("帮阅读加一个每日 30 分钟的阶段")

        // 全链路:行动 → 派发 → 观测回填 → 答复,trace 逐事件可查
        assertEquals(
            AgentOutcome.Answered(
                "已添加每日阶段",
                listOf(
                    AgentEvent.Thought("给阅读加每日阶段"),
                    AgentEvent.Action("set_goal", args),
                    AgentEvent.Observation(expectedObservation),
                    AgentEvent.Final("已添加每日阶段"),
                ),
            ),
            outcome,
        )
        // executor 恰好收到正确的 command 与注入的 now/zone
        assertEquals(1, executor.dispatchCount)
        assertEquals(
            listOf<AttentionCommand>(AttentionCommand.AddGoalStage("flow-target", GoalCadence.DAILY, 30, "2026-03-06")),
            executor.commands,
        )
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
    }

    @Test
    fun auto_append_one_time_stage_starts_at_the_planning_day_and_reports_the_new_summary() = runTest {
        val state = achievedState()
        val executor = ExecutingExecutor(state)
        val tool = SetGoalTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "append_one_time_stage")
            put("targetId", "flow-target")
            put("targetMinutes", 20)
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("追加下一阶段", ActionRequest("set_goal", args)),
            ReasonerDecision.Answer(null, "已追加下一阶段"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("阅读这一阶段达成了,接着安排下一阶段")

        assertEquals("已追加下一阶段", (outcome as AgentOutcome.Answered).text)
        assertEquals(1, executor.dispatchCount)
        val command = executor.commands.single() as AttentionCommand.AppendOneTimeGoalStage
        assertEquals("flow-target", command.targetId)
        assertEquals(20, command.targetMinutes)
        // 观测读到执行后的真实汇总:新阶段从规划日开始,两个一次性阶段合计目标 50
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(!observation.isError)
        assertEquals(
            buildJsonObject {
                putJsonArray("targets") {
                    add(buildJsonObject {
                        put("id", "flow-target")
                        put("title", "阅读")
                        put("archived", false)
                        put("targetMinutes", 50)
                        put("actualMinutes", 30)
                        put("gapMinutes", 20)
                        put("completed", false)
                    })
                }
                put("count", 1)
            },
            observation.payload,
        )
    }

    @Test
    fun add_future_rule_short_circuits_and_confirm_dispatches_the_held_command_once() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "add_future_rule")
            put("targetId", "flow-target")
            put("cadence", "WEEKLY")
            put("targetMinutes", 45)
            put("effectiveFrom", "2026-03-07")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("安排未来的目标", ActionRequest("set_goal", args)),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor, gate)).run("从下周起把阅读提到每周 45 分钟")

        // 请求即短路:不派发、不回填观测、不消耗后续步数
        val pending = outcome as AgentOutcome.PendingConfirmation
        assertEquals(0, executor.dispatchCount)
        assertEquals(1, reasoner.histories.size)
        assertEquals(
            listOf(
                AgentEvent.Thought("安排未来的目标"),
                AgentEvent.Action("set_goal", args),
                AgentEvent.PendingConfirmation("set_goal", args, pending.token),
            ),
            pending.trace,
        )
        assertEquals(
            buildJsonObject { put("tool", "set_goal"); put("args", args) },
            pending.preview,
        )

        // 确认后经同一执行通道恰好派发一次,command 是短路时解析持有的那条
        assertTrue(gate.confirm(pending.token) is ConfirmationResolution.Executed)
        assertEquals(1, executor.dispatchCount)
        val command = executor.commands.single() as AttentionCommand.AddFutureGoalRule
        assertEquals("flow-target", command.rule.targetId)
        assertEquals(GoalCadence.WEEKLY, command.rule.cadence)
        assertEquals(45, command.rule.targetMinutes)
        assertEquals("2026-03-07", command.rule.effectiveFrom)
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
    }

    @Test
    fun cancel_future_rule_short_circuits_and_user_refusal_dispatches_nothing() = runTest {
        val rule = FutureGoalRule(id = "flow-rule", targetId = "flow-target", cadence = GoalCadence.WEEKLY, targetMinutes = 45, effectiveFrom = "2026-03-07")
        val state = baseState().copy(futureGoalRules = listOf(rule))
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "cancel_future_rule")
            put("targetId", "flow-target")
            put("ruleId", "flow-rule")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求取消未来规则", ActionRequest("set_goal", args)),
        )

        val pending = ReActAgent(reasoner, catalog(tool, executor, gate)).run("取消那条未来规则")
            as AgentOutcome.PendingConfirmation

        // 拒绝即裁决:executor 零派发,数据保持原样;同一令牌再确认不得派发
        assertEquals(ConfirmationResolution.Cancelled, gate.cancel(pending.token))
        assertEquals(0, executor.dispatchCount)
        assertEquals(ConfirmationResolution.AlreadyResolved, gate.confirm(pending.token))
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun add_migration_short_circuits_and_confirm_dispatches_once() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "add_migration")
            put("targetId", "flow-target")
            put("sourceStageId", "flow-stage")
            put("minutes", 10)
            put("destinationStartDate", "2026-03-07")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("安排目标迁移", ActionRequest("set_goal", args)),
        )

        val pending = ReActAgent(reasoner, catalog(tool, executor, gate)).run("把 10 分钟缺口挪到下周")
            as AgentOutcome.PendingConfirmation

        assertEquals(0, executor.dispatchCount)
        assertTrue(gate.confirm(pending.token) is ConfirmationResolution.Executed)
        assertEquals(1, executor.dispatchCount)
        val command = executor.commands.single() as AttentionCommand.AddMigration
        assertEquals("flow-target", command.migration.targetId)
        assertEquals("flow-stage", command.migration.sourceStageId)
        assertEquals(10, command.migration.minutes)
        assertEquals("2026-03-07", command.migration.destinationStartDate)
    }

    @Test
    fun an_unknown_rule_id_becomes_an_error_observation_without_dispatching() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "cancel_future_rule")
            put("targetId", "flow-target")
            put("ruleId", "stranger")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("凭记忆取消规则", ActionRequest("set_goal", args)),
            ReasonerDecision.Answer(null, "没有找到那条规则"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor, gate)).run("取消未来规则")

        // 解析相位预校验失败:错误观测回填自纠,确认级动作也不为无效请求铸令牌
        assertEquals("没有找到那条规则", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("未来目标规则不存在") == true)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun a_confirmation_action_with_the_switch_off_becomes_an_error_observation_without_a_token_or_dispatch() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "add_future_rule")
            put("targetId", "flow-target")
            put("cadence", "WEEKLY")
            put("targetMinutes", 45)
            put("effectiveFrom", "2026-03-07")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("安排未来的目标", ActionRequest("set_goal", args)),
            ReasonerDecision.Answer(null, "需要确认的操作暂时不可用"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(tool), executor = executor, clock = clock, gate = gate, confirmationEnabled = false),
        ).run("从下周起把阅读提到每周 45 分钟")

        // 降级开关关闭:确认级动作整体不可达——错误观测回填,不铸令牌、不派发,绝不静默转自动
        assertEquals("需要确认的操作暂时不可用", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("需确认操作暂不可用") == true)
        assertEquals(0, executor.dispatchCount)
    }
}
