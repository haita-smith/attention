package com.attention.agent

import com.attention.domain.AttentionCommand
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变更执行通道的语义:自动级动作经 CommandExecutor 恰好派发一次、now/zone 来自注入时钟、
 * 执行后投影以观测回填,循环继续直至答复——这是最高接缝(ReActAgent.run)上的全链路验收。
 */
class ChangeExecutionTest {

    @Test
    fun auto_level_change_dispatches_via_executor_then_backfills_observation_and_loops_on() = runTest {
        val args = buildJsonObject { put("note", "补记 30 分钟") }
        val tool = ScriptedChangeTool()
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("先补记时间", ActionRequest("stop_timer", args)),
            ReasonerDecision.Answer(null, "已记录"),
        )
        val agent = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(tool), executor = executor, clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) }),
        )

        val outcome = agent.run("补记时间")

        // 全链路:行动 → 派发 → 观测回填 → 继续推理 → 最终答复
        assertEquals(
            AgentOutcome.Answered(
                "已记录",
                listOf(
                    AgentEvent.Thought("先补记时间"),
                    AgentEvent.Action("stop_timer", args),
                    AgentEvent.Observation(buildJsonObject { put("stopped", true) }),
                    AgentEvent.Final("已记录"),
                ),
            ),
            outcome,
        )
        // executor 恰好收到正确 command 与注入的 now/zone,且只派发一次
        assertEquals(1, executor.dispatchCount)
        assertEquals(listOf<AttentionCommand>(AttentionCommand.StopTimer), executor.commands)
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
        // 工具解析相拿到的是行动参数；渲染相拿到的是 executor 的返回结果
        assertEquals(listOf(args), tool.parsedArgs)
        assertEquals(listOf(DISPATCHED_RESULT), tool.renderedResults)
        // 观测回填是 ReAct 的核心:第二轮推理必须看得到第一轮的观测
        assertEquals(
            AgentEvent.Observation(buildJsonObject { put("stopped", true) }),
            reasoner.histories[1].last(),
        )
    }

    @Test
    fun change_tool_parse_failure_becomes_an_error_observation_without_dispatching() = runTest {
        val tool = ThrowingChangeTool(phase = ChangeToolPhase.COMMAND)
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("试着变更", ActionRequest("boom_change")),
            ReasonerDecision.Answer(null, "绕过去了"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("请求")

        // 解析失败是工具侧失败：错误观测回填自纠，command 不派发
        assertEquals("绕过去了", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertEquals(JsonPrimitive("变更工具炸了"), observation.payload["error"])
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun change_tool_render_failure_becomes_an_error_observation_after_dispatch() = runTest {
        val tool = ThrowingChangeTool(phase = ChangeToolPhase.RENDER)
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("试着变更", ActionRequest("boom_change")),
            ReasonerDecision.Answer(null, "绕过去了"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("请求")

        // 渲染失败同样是工具侧失败；但 command 已经派发，这一事实不因投影失败而消失
        assertTrue(outcome is AgentOutcome.Answered)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertEquals(1, executor.dispatchCount)
    }

    @Test
    fun change_tool_risk_query_failure_becomes_an_error_observation_without_dispatching() = runTest {
        val tool = ThrowingChangeTool(phase = ChangeToolPhase.RISK)
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("试着变更", ActionRequest("boom_change")),
            ReasonerDecision.Answer(null, "绕过去了"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("请求")

        // 风险判定失败时走向未知：不派发是最安全的选择，错误观测让推理器自行纠正
        assertTrue(outcome is AgentOutcome.Answered)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun executor_failure_converges_to_a_failed_terminal_without_throwing() = runTest {
        val tool = ScriptedChangeTool()
        val executor = FakeCommandExecutor(failure = IllegalStateException("通道炸了"))
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求变更", ActionRequest("stop_timer")),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("请求")

        // 执行通道故障不是推理器换个问法能修复的：收敛为失败终态，循环不向调用方抛出
        assertTrue(outcome is AgentOutcome.Failed)
        assertTrue((outcome as AgentOutcome.Failed).reason.contains("通道炸了"))
        // 行动事件保留（它确实发生了），但没有可回填的观测；command 恰好派发过一次
        assertEquals(
            listOf(
                AgentEvent.Thought("请求变更"),
                AgentEvent.Action("stop_timer", JsonObject(emptyMap())),
            ),
            outcome.trace,
        )
        assertEquals(1, executor.dispatchCount)
    }

    @Test
    fun confirmation_level_action_is_unreachable_while_the_gate_is_absent() = runTest {
        val tool = ScriptedChangeTool(riskLevels = setOf(RiskLevel.Confirmation), risk = RiskLevel.Confirmation)
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求需确认的变更", ActionRequest("stop_timer")),
            ReasonerDecision.Answer(null, "该类操作暂不可用"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("请求")

        // 确认通道不存在即该级动作整体不可达：错误观测回填，无令牌、无派发、绝不静默转自动
        assertEquals("该类操作暂不可用", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertEquals(JsonPrimitive("需确认操作暂不可用"), observation.payload["error"])
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun a_tool_lying_about_its_levels_at_runtime_is_stopped_without_dispatching() = runTest {
        // 声明只含 Auto 的工具运行期越级返回 Forbidden：注册合法，执行被防御分支拦下
        val lying = ScriptedChangeTool(riskLevels = setOf(RiskLevel.Auto), risk = RiskLevel.Forbidden)
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求变更", ActionRequest("stop_timer")),
            ReasonerDecision.Answer(null, "被拦下了"),
        )

        val outcome = ReActAgent(reasoner, catalog(lying, executor)).run("请求")

        assertTrue(outcome is AgentOutcome.Answered)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertEquals(0, executor.dispatchCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun forbidden_level_change_tools_are_rejected_at_construction() {
        ToolCatalog(
            changeTools = listOf(ScriptedChangeTool(riskLevels = setOf(RiskLevel.Auto, RiskLevel.Forbidden))),
            executor = FakeCommandExecutor(),
            clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) },
        )
    }

    @Test
    fun catalogs_that_construct_successfully_declare_no_forbidden_level() {
        val catalog = ToolCatalog(
            changeTools = listOf(
                ScriptedChangeTool(name = "auto_tool", riskLevels = setOf(RiskLevel.Auto)),
                ScriptedChangeTool(name = "confirm_tool", riskLevels = setOf(RiskLevel.Confirmation), risk = RiskLevel.Confirmation),
            ),
            executor = FakeCommandExecutor(),
            clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) },
        )

        assertTrue(catalog.changeTools.flatMap { it.riskLevels }.none { it == RiskLevel.Forbidden })
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicate_change_tool_names_are_rejected_at_construction() {
        ToolCatalog(
            changeTools = listOf(ScriptedChangeTool(name = "dup"), ScriptedChangeTool(name = "dup")),
            executor = FakeCommandExecutor(),
            clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_change_tool_colliding_with_a_read_tool_name_is_rejected_at_construction() {
        ToolCatalog(
            tools = listOf(RecordingTool(name = "dup")),
            changeTools = listOf(ScriptedChangeTool(name = "dup")),
            executor = FakeCommandExecutor(),
            clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun change_tools_without_an_executor_are_rejected_at_construction() {
        ToolCatalog(changeTools = listOf(ScriptedChangeTool()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun change_tools_without_a_clock_source_are_rejected_at_construction() {
        ToolCatalog(changeTools = listOf(ScriptedChangeTool()), executor = FakeCommandExecutor())
    }

    private fun catalog(tool: ChangeTool, executor: CommandExecutor) = ToolCatalog(
        changeTools = listOf(tool),
        executor = executor,
        clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) },
    )
}
