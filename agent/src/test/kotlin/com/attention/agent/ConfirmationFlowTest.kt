package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认级动作的短路与回调语义(最高接缝 ReActAgent.run 的终态与轨迹):
 * 请求即短路——不执行、不回填观测、不消耗后续步数,以待确认终态收敛;
 * 用户的裁决经确认回调入口异步到达,确认才经同一 executor 派发,令牌一次性消费。
 */
class ConfirmationFlowTest {

    private val clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) }

    private fun confirmationTool() =
        ScriptedChangeTool(riskLevels = setOf(RiskLevel.Confirmation), risk = RiskLevel.Confirmation)

    private fun catalog(tool: ChangeTool, executor: CommandExecutor, gate: ConfirmationGate) = ToolCatalog(
        changeTools = listOf(tool),
        executor = executor,
        clock = clock,
        gate = gate,
    )

    /** 第一次派发抛出注入异常、之后放行的执行器,用来验证通道故障与取消不吞掉裁决机会。 */
    private class FaultThenPassExecutor(private val fault: Throwable) : CommandExecutor {
        val dispatchCount: Int get() = dispatched

        private var dispatched = 0
        private var faulted = false

        override suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult {
            if (!faulted) {
                faulted = true
                throw fault
            }
            dispatched += 1
            return DISPATCHED_RESULT
        }
    }

    @Test
    fun confirmation_level_action_short_circuits_without_dispatching_or_consuming_steps() = runTest {
        val args = buildJsonObject { put("target", "readathon") }
        val tool = confirmationTool()
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        // 脚本只有一次决策:循环若不短路而继续,推理器脚本耗尽会以 Failed 收敛——
        // 断言到待确认终态本身,即证明短路没有回填观测、没有消耗后续步数
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求需确认的变更", ActionRequest("stop_timer", args)),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor, gate)).run("请求")

        assertTrue(outcome is AgentOutcome.PendingConfirmation)
        val pending = outcome as AgentOutcome.PendingConfirmation
        // 轨迹:思考、行动、待确认事件;事件与终态携带同一个令牌
        assertEquals(
            listOf(
                AgentEvent.Thought("请求需确认的变更"),
                AgentEvent.Action("stop_timer", args),
                AgentEvent.PendingConfirmation("stop_timer", args, pending.token),
            ),
            pending.trace,
        )
        // 预览载荷:确认卡片要展示的「将要发生什么」——工具与参数
        assertEquals(
            buildJsonObject { put("tool", "stop_timer"); put("args", args) },
            pending.preview,
        )
        // 短路即收敛:command 未派发、无第二轮推理、无观测回填
        assertEquals(0, executor.dispatchCount)
        assertEquals(1, reasoner.histories.size)
        assertTrue(tool.renderedResults.isEmpty())
        // command 在短路时已解析持有,回调派发不得要求重新解析
        assertEquals(listOf<JsonObject>(args), tool.parsedArgs)
    }

    @Test
    fun confirm_dispatches_the_held_command_once_via_the_same_executor_and_returns_executed() = runTest {
        val tool = confirmationTool()
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求需确认的变更", ActionRequest("stop_timer")),
        )

        val pending = ReActAgent(reasoner, catalog(tool, executor, gate)).run("请求")
            as AgentOutcome.PendingConfirmation

        val resolution = gate.confirm(pending.token)

        // 同一 executor、短路时持有的 command 与短路时刻的时钟快照,恰好派发一次
        assertEquals(ConfirmationResolution.Executed(DISPATCHED_RESULT), resolution)
        assertEquals(1, executor.dispatchCount)
        assertEquals(listOf<AttentionCommand>(AttentionCommand.StopTimer), executor.commands)
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
        // 回调路径不回填观测:工具的渲染相不发生
        assertTrue(tool.renderedResults.isEmpty())
    }

    @Test
    fun cancel_resolves_without_dispatching_and_consumes_the_token() = runTest {
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求需确认的变更", ActionRequest("stop_timer")),
        )

        val pending = ReActAgent(reasoner, catalog(confirmationTool(), executor, gate)).run("请求")
            as AgentOutcome.PendingConfirmation

        // 拒绝后 executor 零调用,数据保持原样
        assertEquals(ConfirmationResolution.Cancelled, gate.cancel(pending.token))
        assertEquals(0, executor.dispatchCount)
        // 取消也是裁决:同一令牌再确认不得派发
        assertEquals(ConfirmationResolution.AlreadyResolved, gate.confirm(pending.token))
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun unknown_and_consumed_tokens_are_valued_not_thrown() = runTest {
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val stranger = ConfirmationToken("从未签发")

        // 未知令牌:确认与取消都返回值,不抛异常
        assertEquals(ConfirmationResolution.UnknownToken, gate.confirm(stranger))
        assertEquals(ConfirmationResolution.UnknownToken, gate.cancel(stranger))
        assertEquals(0, executor.dispatchCount)

        val token = gate.register(AttentionCommand.StopTimer)
        assertEquals(ConfirmationResolution.Executed(DISPATCHED_RESULT), gate.confirm(token))
        // 令牌一次性:确认之后无论再确认还是再取消都是已裁决,command 不会被执行成两笔
        assertEquals(ConfirmationResolution.AlreadyResolved, gate.confirm(token))
        assertEquals(ConfirmationResolution.AlreadyResolved, gate.cancel(token))
        assertEquals(1, executor.dispatchCount)
    }

    @Test
    fun confirmation_tool_parse_failure_still_becomes_an_error_observation_without_a_token() = runTest {
        val tool = object : ChangeTool {
            override val name = "fragile_confirm"
            override val description = "解析即失败的确认级工具"
            override val parametersSchema = buildJsonObject { put("type", "object") }
            override val riskLevels = setOf(RiskLevel.Confirmation)
            override fun riskLevel(args: JsonObject) = RiskLevel.Confirmation
            override fun command(args: JsonObject): AttentionCommand =
                throw IllegalArgumentException("参数不合法")

            override fun observation(args: JsonObject, result: AttentionCommandResult) = buildJsonObject { }
        }
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求确认级变更", ActionRequest("fragile_confirm")),
            ReasonerDecision.Answer(null, "绕过去了"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor, gate)).run("请求")

        // 解析失败在登记之前:错误观测回填自纠,不产生令牌、不短路、不派发
        assertEquals("绕过去了", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun executor_fault_during_confirm_is_not_a_resolution_and_leaves_the_token_decidable() = runTest {
        val executor = FaultThenPassExecutor(IllegalStateException("通道炸了"))
        val gate = ConfirmationGate(executor, clock)
        val token = gate.register(AttentionCommand.StopTimer)

        // 执行通道故障原样上行,不是裁决值
        val thrown = runCatching { gate.confirm(token) }

        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        // 通道故障不吞掉裁决机会:令牌回到待裁决,可取消止血、可换路重试
        assertEquals(ConfirmationResolution.Cancelled, gate.cancel(token))
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun cancellation_during_confirm_leaves_the_token_pending_and_retryable() = runTest {
        val executor = FaultThenPassExecutor(kotlinx.coroutines.CancellationException("调用方要停"))
        val gate = ConfirmationGate(executor, clock)
        val token = gate.register(AttentionCommand.StopTimer)

        val thrown = runCatching { gate.confirm(token) }

        // 协程取消穿透回调入口,绝不被吞成结果值
        assertTrue(thrown.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        // 取消不等于裁决:重试确认仍能恰好派发一次
        assertEquals(ConfirmationResolution.Executed(DISPATCHED_RESULT), gate.confirm(token))
        assertEquals(1, executor.dispatchCount)
    }

    @Test
    fun explicitly_enabled_switch_keeps_the_short_circuit_semantics_unchanged() = runTest {
        val tool = confirmationTool()
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求需确认的变更", ActionRequest("stop_timer")),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(
                changeTools = listOf(tool),
                executor = executor,
                clock = clock,
                gate = gate,
                confirmationEnabled = true,
            ),
        ).run("请求")

        // 显式开启与默认开启完全一致:短路终态、令牌在手、command 未派发
        assertTrue(outcome is AgentOutcome.PendingConfirmation)
        assertEquals(0, executor.dispatchCount)
        assertEquals(
            ConfirmationResolution.Executed(DISPATCHED_RESULT),
            gate.confirm((outcome as AgentOutcome.PendingConfirmation).token),
        )
        assertEquals(1, executor.dispatchCount)
    }

    @Test
    fun pending_confirmation_chain_survives_a_json_round_trip() {
        val token = ConfirmationToken("confirm-7")
        val args = buildJsonObject { put("target", "readathon") }
        val trace = listOf(
            AgentEvent.Thought("请求需确认的变更"),
            AgentEvent.PendingConfirmation("stop_timer", args, token),
        )
        val outcome: AgentOutcome = AgentOutcome.PendingConfirmation(
            token,
            buildJsonObject { put("tool", "stop_timer"); put("args", args) },
            trace,
        )

        assertEquals(trace, Json.decodeFromString<List<AgentEvent>>(Json.encodeToString(trace)))
        assertEquals(outcome, Json.decodeFromString<AgentOutcome>(Json.encodeToString(outcome)))
    }
}
