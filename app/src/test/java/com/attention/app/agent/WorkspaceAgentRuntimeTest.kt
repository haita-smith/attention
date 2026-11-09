package com.attention.app.agent

import com.attention.agent.ActionRequest
import com.attention.agent.AgentEvent
import com.attention.agent.AgentOutcome
import com.attention.agent.AgentTool
import com.attention.agent.ChangeTool
import com.attention.agent.CommandExecutor
import com.attention.agent.ConfirmationToken
import com.attention.agent.ConfirmationResolution
import com.attention.agent.Reasoner
import com.attention.agent.ReasonerDecision
import com.attention.agent.RiskLevel
import com.attention.app.data.audit.AgentRunOutcome
import com.attention.app.data.audit.AgentTraceEntry
import com.attention.app.data.audit.AgentTraceStore
import com.attention.app.data.audit.AiChangeAudit
import com.attention.app.data.audit.AiSnapshotStore
import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时公开面的契约(不窥探装配内部):
 * - run 的运行边界内收集 command 名单、按终态映射结局、request 原样记一条摘要;
 * - 只读路径零快照;快照写失败收敛为 Failed 终态且变更不落库;
 * - 降级开关首版关闭:确认级动作错误观测、无令牌、零派发,自动级不受影响;
 * - 确认门裁决经运行时转发;时钟每次派发现取,不在构造期冻结。
 */
class WorkspaceAgentRuntimeTest {

    private fun runtime(
        reasoner: Reasoner,
        dispatch: CommandExecutor = FakeDispatchExecutor(),
        snapshots: AiSnapshotStore = RecordingSnapshotStore(),
        traces: AgentTraceStore = RecordingTraceStore(),
        readOnlyTools: List<AgentTool>? = null,
        changeTools: List<ChangeTool>? = null,
        confirmationEnabled: Boolean = false,
        maxSteps: Int = 4,
        now: () -> Instant = { DISPATCH_NOW },
        zone: () -> ZoneId = { DISPATCH_ZONE },
    ): WorkspaceAgentRuntime = WorkspaceAgentRuntime(
        dispatch = dispatch,
        audit = AiChangeAudit(traces, snapshots),
        state = { AttentionState() },
        now = now,
        zone = zone,
        reasoner = reasoner,
        readOnlyTools = readOnlyTools,
        changeTools = changeTools,
        confirmationEnabled = confirmationEnabled,
        maxSteps = maxSteps,
    )

    @Test
    fun a_finished_run_records_one_summary_with_request_outcome_and_command_names() = runBlocking {
        val traces = RecordingTraceStore()
        // 同一工具的三次调用映射两条不同 command:executor 是名单的唯一准确观测点,
        // 审计门面按首次出现去重
        val tool = ScriptedChangeTool(
            commandQueue = ArrayDeque(
                listOf(AttentionCommand.StopTimer, AttentionCommand.DeleteTimeEntry("e1"), AttentionCommand.StopTimer),
            ),
        )
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act("记一笔", ActionRequest("scripted_change")),
            ReasonerDecision.Act("再记一笔", ActionRequest("scripted_change")),
            ReasonerDecision.Act("又记一笔", ActionRequest("scripted_change")),
            ReasonerDecision.Answer.withoutThought("已记录"),
        )
        val agentRuntime = runtime(reasoner, traces = traces, changeTools = listOf(tool))

        val outcome = agentRuntime.run("帮我记 25 分钟阅读")

        assertTrue(outcome is AgentOutcome.Answered)
        val entry = traces.recorded.single()
        assertEquals("帮我记 25 分钟阅读", entry.request)
        assertEquals(AgentRunOutcome.ANSWERED, entry.outcome)
        assertEquals(listOf("StopTimer", "DeleteTimeEntry"), entry.commands)
    }

    @Test
    fun a_budget_exhausted_run_maps_to_budget_exhausted() = runBlocking {
        val traces = RecordingTraceStore()
        val tool = ScriptedChangeTool()
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act("记一笔", ActionRequest("scripted_change")),
        )
        val agentRuntime = runtime(reasoner, traces = traces, changeTools = listOf(tool), maxSteps = 1)

        val outcome = agentRuntime.run("预算耗尽的请求")

        assertTrue(outcome is AgentOutcome.BudgetExhausted)
        assertEquals(AgentRunOutcome.BUDGET_EXHAUSTED, traces.recorded.single().outcome)
        assertEquals(listOf("StopTimer"), traces.recorded.single().commands)
    }

    @Test
    fun a_failed_reasoner_maps_to_failed() = runBlocking {
        val traces = RecordingTraceStore()
        // 空脚本推理器第一次被调即抛:循环把它收敛为 Failed 终态
        val agentRuntime = runtime(ScriptedReasoner(), traces = traces)

        val outcome = agentRuntime.run("会失败的请求")

        assertTrue(outcome is AgentOutcome.Failed)
        assertEquals(AgentRunOutcome.FAILED, traces.recorded.single().outcome)
        assertEquals("会失败的请求", traces.recorded.single().request)
    }

    @Test
    fun a_read_only_run_writes_no_snapshot_and_no_command_names() = runBlocking {
        val traces = RecordingTraceStore()
        val snapshots = RecordingSnapshotStore()
        val query = ScriptedReadOnlyTool()
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act("查一下", ActionRequest("scripted_query")),
            ReasonerDecision.Answer.withoutThought("结果如下"),
        )
        val agentRuntime = runtime(reasoner, traces = traces, snapshots = snapshots, readOnlyTools = listOf(query))

        val outcome = agentRuntime.run("今天的目标进度如何")

        assertTrue(outcome is AgentOutcome.Answered)
        assertEquals(emptyList<AttentionState>(), snapshots.captured)
        assertEquals(emptyList<String>(), traces.recorded.single().commands)
        assertEquals(1, query.calls.size)
    }

    @Test
    fun a_failed_snapshot_turns_the_change_into_a_failed_run_without_dispatch() = runBlocking {
        val traces = RecordingTraceStore()
        val snapshots = RecordingSnapshotStore(failure = IllegalStateException("快照写失败"))
        val dispatch = FakeDispatchExecutor()
        val tool = ScriptedChangeTool()
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act("记一笔", ActionRequest("scripted_change")),
            ReasonerDecision.Answer.withoutThought("不应到达"),
        )
        val agentRuntime = runtime(reasoner, dispatch = dispatch, snapshots = snapshots, traces = traces, changeTools = listOf(tool))

        val outcome = agentRuntime.run("记一笔")

        // 快照写失败即变更失败:执行通道故障由循环收敛为失败终态,数据零落库
        assertTrue(outcome is AgentOutcome.Failed)
        assertEquals(0, dispatch.dispatchCount)
        assertEquals(AgentRunOutcome.FAILED, traces.recorded.single().outcome)
        assertEquals(emptyList<String>(), traces.recorded.single().commands)
    }

    @Test
    fun a_confirmation_level_action_is_unavailable_while_the_switch_is_off() = runBlocking {
        val traces = RecordingTraceStore()
        val dispatch = FakeDispatchExecutor()
        val confirmationTool = ScriptedChangeTool(
            name = "confirm_tool",
            risk = RiskLevel.Confirmation,
            commandQueue = ArrayDeque(listOf(AttentionCommand.ArchiveTarget("t1"))),
        )
        val autoTool = ScriptedChangeTool(
            name = "auto_tool",
            risk = RiskLevel.Auto,
            commandQueue = ArrayDeque(listOf(AttentionCommand.StopTimer)),
        )
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act("想归档", ActionRequest("confirm_tool")),
            ReasonerDecision.Act("改走自动级", ActionRequest("auto_tool")),
            ReasonerDecision.Answer.withoutThought("归档暂不可用,已替你停表"),
        )
        val agentRuntime = runtime(
            reasoner,
            dispatch = dispatch,
            traces = traces,
            changeTools = listOf(confirmationTool, autoTool),
        )

        val outcome = agentRuntime.run("帮我归档目标")

        // 终态是答复而非待确认:无令牌、无短路
        assertTrue(outcome is AgentOutcome.Answered)
        assertTrue(
            "轨迹里不得出现待确认事件",
            reasoner.histories.last().none { it is AgentEvent.PendingConfirmation },
        )
        // 确认级零派发、自动级恰一次
        assertEquals(listOf<AttentionCommand>(AttentionCommand.StopTimer), dispatch.commands)
        // 错误观测「需确认操作暂不可用」回填给了推理器
        val observations = reasoner.histories[1].filterIsInstance<AgentEvent.Observation>()
        assertTrue(
            "确认级动作应得到不可用错误观测",
            observations.any {
                it.isError && it.payload == buildJsonObject { put("error", "需确认操作暂不可用") }
            },
        )
        assertEquals(listOf("StopTimer"), traces.recorded.single().commands)
    }

    @Test
    fun a_pending_confirmation_run_records_no_summary_and_adjudication_is_forwarded() = runBlocking {
        val traces = RecordingTraceStore()
        val snapshots = RecordingSnapshotStore()
        val dispatch = FakeDispatchExecutor()
        val confirmationTool = ScriptedChangeTool(
            name = "confirm_tool",
            risk = RiskLevel.Confirmation,
            commandQueue = ArrayDeque(listOf(AttentionCommand.ArchiveTarget("t1"))),
        )
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act("归档", ActionRequest("confirm_tool")),
        )
        val agentRuntime = runtime(
            reasoner,
            dispatch = dispatch,
            traces = traces,
            snapshots = snapshots,
            changeTools = listOf(confirmationTool),
            confirmationEnabled = true,
            maxSteps = 1,
        )

        val outcome = agentRuntime.run("帮我归档目标")

        // 待确认终态短路:command 未派发、运行摘要待裁决线扩展(当前不记)
        assertTrue(outcome is AgentOutcome.PendingConfirmation)
        assertEquals(0, dispatch.dispatchCount)
        assertEquals(emptyList<AgentTraceEntry>(), traces.recorded)

        // 确认:经运行时转发到确认门,由被包装的通道派发,派发前先快照
        val token = (outcome as AgentOutcome.PendingConfirmation).token
        val resolution = agentRuntime.confirm(token)
        assertTrue(resolution is ConfirmationResolution.Executed)
        assertEquals(1, dispatch.dispatchCount)
        assertEquals(1, snapshots.captured.size)

        // 令牌一次性消费;未知令牌以值回答
        assertEquals(ConfirmationResolution.AlreadyResolved, agentRuntime.cancel(token))
        assertEquals(ConfirmationResolution.UnknownToken, agentRuntime.cancel(ConfirmationToken("999")))
    }

    @Test
    fun the_clock_is_sampled_fresh_at_each_dispatch_not_frozen_at_construction() = runBlocking {
        var tick = 0L
        val dispatch = FakeDispatchExecutor()
        val tool = ScriptedChangeTool(commandQueue = ArrayDeque(List(4) { AttentionCommand.StopTimer }))
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act("第一次第一笔", ActionRequest("scripted_change")),
            ReasonerDecision.Act("第一次第二笔", ActionRequest("scripted_change")),
            ReasonerDecision.Answer.withoutThought("第一次完成"),
            ReasonerDecision.Act("第二次第一笔", ActionRequest("scripted_change")),
            ReasonerDecision.Act("第二次第二笔", ActionRequest("scripted_change")),
            ReasonerDecision.Answer.withoutThought("第二次完成"),
        )
        val agentRuntime = runtime(
            reasoner,
            dispatch = dispatch,
            changeTools = listOf(tool),
            now = { Instant.ofEpochMilli(tick) },
        )

        tick = 1_000
        agentRuntime.run("第一次运行")
        tick = 2_000
        agentRuntime.run("第二次运行")

        // 每次派发现取时钟:第二次运行看到推进后的现值;
        // 若时钟在构造期被冻结,这里会得到四个 1000
        assertEquals(listOf(1_000L, 1_000L, 2_000L, 2_000L), dispatch.nows.map { it.toEpochMilli() })
    }

    @Test
    fun the_default_catalog_dispatches_a_real_auto_change_end_to_end() = runBlocking {
        val traces = RecordingTraceStore()
        val snapshots = RecordingSnapshotStore()
        val reasoner = ScriptedReasoner(
            ReasonerDecision.Act(
                "创建目标",
                ActionRequest(
                    "manage_target",
                    buildJsonObject {
                        put("action", "create")
                        put("title", "雅思")
                    },
                ),
            ),
            ReasonerDecision.Answer.withoutThought("已创建"),
        )
        // 不传工具清单:走生产装配(只读四件 + 变更四件套,WorkspaceSource 注入)
        val agentRuntime = runtime(reasoner, traces = traces, snapshots = snapshots)

        val outcome = agentRuntime.run("帮我建一个目标")

        assertTrue(outcome is AgentOutcome.Answered)
        assertEquals(listOf("CreateTarget"), traces.recorded.single().commands)
        assertEquals(1, snapshots.captured.size)
    }
}
