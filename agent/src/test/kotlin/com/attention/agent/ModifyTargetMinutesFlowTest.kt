package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.GoalCadence
import com.attention.domain.GoalStage
import com.attention.domain.Target
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * modify_target_minutes(#243)在最高接缝(ReActAgent.run)上的全链路语义:
 * 确认级请求即短路(不派发、不回填观测、不消耗后续步数),确认后经同一执行通道
 * 恰好派发一次、cancel 零派发;未知阶段 id 与已完成阶段在解析相位转为错误观测,
 * 零派发、不为无效请求铸造令牌。
 */
class ModifyTargetMinutesFlowTest {

    // 时钟与快照同一套事实(ADR-0005):上海时间 2026-03-06 10:00,规划日 2026-03-06(周五)
    private val clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) }

    /** 周阶段(目标 100,起点周一 2026-03-02)所在目标的底座。 */
    private fun baseState(stageCompleted: Boolean = false) = AttentionState(
        targets = listOf(Target("flow-target", title = "阅读")),
        goalStages = listOf(GoalStage("flow-stage", "flow-target", GoalCadence.WEEKLY, 100, "2026-03-02", completed = stageCompleted)),
    )

    private fun sourceOf(state: AttentionState) = WorkspaceSource { WorkspaceView(state, DISPATCH_NOW, DISPATCH_ZONE) }

    /** 真执行领域 command 的执行通道:观测投影读到的是执行后的真实状态。 */
    private class ExecutingExecutor(private val state: AttentionState) : CommandExecutor {
        val commands = mutableListOf<AttentionCommand>()
        val nows = mutableListOf<Instant>()
        val zones = mutableListOf<ZoneId>()

        val dispatchCount: Int get() = commands.size

        override suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult {
            commands += command
            nows += now
            zones += zone
            return command.execute(state, now, zone)
        }
    }

    private fun modifyArgs(stageId: String = "flow-stage", targetMinutes: Int = 50) = buildJsonObject {
        put("action", "modify_target_minutes")
        put("targetId", "flow-target")
        put("stageId", stageId)
        put("targetMinutes", targetMinutes)
    }

    @Test
    fun modify_request_short_circuits_and_confirm_dispatches_the_held_command_once() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val args = modifyArgs()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求修改时间目标", ActionRequest("set_goal", args)),
        )

        val outcome = ReActAgent(reasoner, ToolCatalog(changeTools = listOf(tool), executor = executor, clock = clock, gate = gate))
            .run("把阅读这一阶段改成每周 50 分钟")

        // 请求即短路:不派发、不回填观测、不消耗后续步数
        val pending = outcome as AgentOutcome.PendingConfirmation
        assertEquals(0, executor.dispatchCount)
        assertEquals(1, reasoner.histories.size)
        assertEquals(
            listOf(
                AgentEvent.Thought("请求修改时间目标"),
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
        val command = executor.commands.single() as AttentionCommand.UpdateGoalStage
        assertEquals("flow-stage", command.stageId)
        assertEquals(50, command.targetMinutes)
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
    }

    @Test
    fun user_refusal_dispatches_nothing_and_the_token_stays_single_use() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求修改时间目标", ActionRequest("set_goal", modifyArgs())),
        )

        val pending = ReActAgent(reasoner, ToolCatalog(changeTools = listOf(tool), executor = executor, clock = clock, gate = gate))
            .run("把阅读这一阶段改成每周 50 分钟") as AgentOutcome.PendingConfirmation

        // 拒绝即裁决:executor 零派发,数据保持原样;同一令牌再确认不得派发
        assertEquals(ConfirmationResolution.Cancelled, gate.cancel(pending.token))
        assertEquals(0, executor.dispatchCount)
        assertEquals(ConfirmationResolution.AlreadyResolved, gate.confirm(pending.token))
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun an_unknown_stage_id_becomes_an_error_observation_without_a_token() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("凭记忆修改阶段", ActionRequest("set_goal", modifyArgs(stageId = "stranger"))),
            ReasonerDecision.Answer(null, "没有找到那个阶段"),
        )

        val outcome = ReActAgent(reasoner, ToolCatalog(changeTools = listOf(tool), executor = executor, clock = clock, gate = gate))
            .run("把那个阶段改成 50 分钟")

        // 解析相位预校验失败:错误观测回填自纠,确认级动作不为无效请求铸令牌
        assertEquals("没有找到那个阶段", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("目标阶段不存在") == true)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun a_completed_stage_becomes_an_error_observation_without_a_token() = runTest {
        val state = baseState(stageCompleted = true)
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = SetGoalTool(sourceOf(state))
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求修改已完成阶段", ActionRequest("set_goal", modifyArgs())),
            ReasonerDecision.Answer(null, "那个阶段已完成,不能修改"),
        )

        val outcome = ReActAgent(reasoner, ToolCatalog(changeTools = listOf(tool), executor = executor, clock = clock, gate = gate))
            .run("把已完成的那一阶段改成 50 分钟")

        assertEquals("那个阶段已完成,不能修改", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("已完成") == true)
        assertEquals(0, executor.dispatchCount)
    }
}
