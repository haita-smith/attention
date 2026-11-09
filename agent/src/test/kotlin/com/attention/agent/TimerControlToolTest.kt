package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.addTarget
import com.attention.domain.pauseTimer
import com.attention.domain.startTimer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * timer_control 工具(#245)的外部行为:5 个 action 的 args → 风险级别(全 Auto)、
 * 领域 command 字段、观测形状三段映射;schema 可解析且字段描述齐;解析相预校验把
 * 未知目标 id 挡在派发之前。循环级全链路(开始→切换→停止 command 序列、跨规划日
 * 拆分投影、无计时器 stop 幂等、同目标切换幂等、错误观测零派发)在同一文件后半部,
 * 均在最高接缝(ReActAgent.run)上验证。
 */
class TimerControlToolTest {

    private val clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) }

    private fun toolOf(state: AttentionState) =
        TimerControlTool(WorkspaceSource { WorkspaceView(state, DISPATCH_NOW, DISPATCH_ZONE) })

    private fun stateOf(vararg titles: String): AttentionState = titles.fold(AttentionState()) { state, title -> state.addTarget(title) }

    private fun runningOn(state: AttentionState, targetId: String, startedAt: Instant) =
        state.startTimer(targetId, startedAt, DISPATCH_ZONE)

    /** 与工具实现相互独立的计时记录条目字面量(不含 scheduleEntryId:红线)。 */
    private fun entry(id: String, planningDate: String, durationMinutes: Int, targetId: String?, title: String): JsonObject =
        buildJsonObject {
            put("id", id)
            put("planningDate", planningDate)
            put("durationMinutes", durationMinutes)
            targetId?.let { put("targetId", it) }
            put("targetTitle", title)
            put("source", "TIMER")
        }

    /** 观测形状的期望:计时态对齐 query_timer,结算条目只在有 entries 时出现。 */
    private fun observation(
        running: Boolean,
        paused: Boolean? = null,
        targetTitle: String? = null,
        targetId: String? = null,
        elapsedMillis: Long? = null,
        entries: List<JsonObject>? = null,
    ): JsonObject = buildJsonObject {
        put("running", running)
        paused?.let { put("paused", it) }
        targetTitle?.let { put("targetTitle", it) }
        targetId?.let { put("targetId", it) }
        elapsedMillis?.let { put("elapsedMillis", it) }
        entries?.let { settled ->
            putJsonArray("entries") { settled.forEach { add(it) } }
            put("totalMinutes", settled.sumOf { it["durationMinutes"]?.jsonPrimitive?.content?.toInt() ?: 0 })
        }
    }

    /** 一行映射:同一份 args 依次喂给三相,fixture 供预校验与执行后状态推演。 */
    private data class Row(
        val name: String,
        val fixture: AttentionState,
        val args: JsonObject,
        val command: AttentionCommand,
        val observationOf: (AttentionState) -> JsonObject,
    )

    @Test
    fun every_action_maps_args_to_auto_risk_its_command_and_timer_observation() {
        val idle = stateOf("专注", "写作")
        val focus = idle.targets[0]
        val writing = idle.targets[1]
        val started = runningOn(idle, focus.id, DISPATCH_NOW.minusSeconds(15 * 60L))
        val paused = idle
            .startTimer(focus.id, DISPATCH_NOW.minusSeconds(10 * 60L), DISPATCH_ZONE)
            .pauseTimer(DISPATCH_NOW.minusSeconds(4 * 60L))

        val rows = listOf(
            Row(
                "start 未归属", idle,
                buildJsonObject { put("action", "start") },
                AttentionCommand.StartTimer(null),
                { observation(running = true, paused = false, targetTitle = "未归属活动", elapsedMillis = 0) },
            ),
            Row(
                "start 归属目标", idle,
                buildJsonObject { put("action", "start"); put("targetId", focus.id) },
                AttentionCommand.StartTimer(focus.id),
                { observation(running = true, paused = false, targetTitle = "专注", targetId = focus.id, elapsedMillis = 0) },
            ),
            Row(
                "pause", started,
                buildJsonObject { put("action", "pause") },
                AttentionCommand.PauseTimer,
                { observation(running = true, paused = true, targetTitle = "专注", targetId = focus.id, elapsedMillis = 900_000) },
            ),
            Row(
                "resume", paused,
                buildJsonObject { put("action", "resume") },
                AttentionCommand.ResumeTimer,
                { observation(running = true, paused = false, targetTitle = "专注", targetId = focus.id, elapsedMillis = 360_000) },
            ),
            Row(
                "switch", started,
                buildJsonObject { put("action", "switch"); put("targetId", writing.id) },
                AttentionCommand.SwitchTimer(writing.id),
                { after ->
                    val settled = after.timeEntries.single()
                    observation(
                        running = true,
                        paused = false,
                        targetTitle = "写作",
                        targetId = writing.id,
                        elapsedMillis = 0,
                        entries = listOf(entry(settled.id, "2026-03-06", 15, focus.id, "专注")),
                    )
                },
            ),
            Row(
                "stop", started,
                buildJsonObject { put("action", "stop") },
                AttentionCommand.StopTimer,
                { after ->
                    val settled = after.timeEntries.single()
                    observation(
                        running = false,
                        entries = listOf(entry(settled.id, "2026-03-06", 15, focus.id, "专注")),
                    )
                },
            ),
        )

        rows.forEach { row ->
            val tool = toolOf(row.fixture)
            assertEquals("${row.name} riskLevel", RiskLevel.Auto, tool.riskLevel(row.args))
            assertEquals("${row.name} command", row.command, tool.command(row.args))
            // 观测相拿的是执行结果(生产上即 executor 的返回):完整结果而非仅状态,
            // stop/switch 的结算条目就在结果的 timeEntries 里
            val executed = row.command.execute(row.fixture, DISPATCH_NOW, DISPATCH_ZONE)
            val payload = tool.observation(row.args, executed)
            assertEquals("${row.name} observation", row.observationOf(executed.state), payload)
            // 红线:投影不含 scheduleEntryId(日程链路本阶段排除),也不含任何平台效果字段
            assertFalse("${row.name} 观测暴露了 scheduleEntryId", payload.containsKey("scheduleEntryId"))
        }
    }

    @Test
    fun schema_round_trips_and_describes_the_action_set_and_every_field() {
        val tool = toolOf(AttentionState())
        val schema = Json.decodeFromString<JsonObject>(Json.encodeToString(tool.parametersSchema))

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("action"), schema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        val properties = schema["properties"]?.jsonObject ?: throw AssertionError("schema 缺少 properties")
        // 字段集恰为 action 与 targetId:不暴露 scheduleEntryId,也不为计时动作引入别的参数
        assertEquals(setOf("action", "targetId"), properties.keys)
        properties.forEach { (field, spec) ->
            assertTrue("字段 $field 缺少描述", spec.jsonObject.containsKey("description"))
        }
        // action 描述列出全部 5 个 action:与映射表同一单一事实来源,永不漂移
        val actionDescription = properties["action"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: ""
        TimerControlTool.ACTION_RISK.keys.forEach { action ->
            assertTrue("action 描述缺少 $action", actionDescription.contains(action))
        }
    }

    @Test
    fun the_action_set_is_exactly_the_five_timer_actions_and_all_auto() {
        val expected = setOf("start", "pause", "resume", "switch", "stop")
        // action 全集恰为 5 个计时动作;分级遵循 #226:计时操作全部即时,全 Auto
        assertEquals(expected, TimerControlTool.ACTION_RISK.keys)
        assertTrue(TimerControlTool.ACTION_RISK.values.all { it == RiskLevel.Auto })
        // Forbidden 不进声明,目录注册因此放行;清单红线在注册后的目录上复核
        val tool = toolOf(AttentionState())
        assertEquals(setOf(RiskLevel.Auto), tool.riskLevels)
        assertThrows(IllegalArgumentException::class.java) { tool.riskLevel(buildJsonObject { put("action", "recover") }) }
        assertThrows(IllegalArgumentException::class.java) { tool.riskLevel(buildJsonObject { }) }
        val catalog = ToolCatalog(changeTools = listOf(tool), executor = FakeCommandExecutor(), clock = clock)
        assertTrue(catalog.changeTools.single().riskLevels.none { it == RiskLevel.Forbidden })
    }

    @Test
    fun parse_phase_rejects_an_unknown_target_before_any_dispatch() {
        val tool = toolOf(stateOf("专注"))

        assertThrows("start 未知目标", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "start"); put("targetId", "missing") })
        }
        assertThrows("switch 未知目标", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "switch"); put("targetId", "missing") })
        }
        // 缺 targetId 是合法语义(未归属计时),不因预校验而误伤
        assertEquals(AttentionCommand.StartTimer(null), tool.command(buildJsonObject { put("action", "start") }))
    }

    @Test
    fun start_then_switch_then_stop_dispatches_the_command_sequence_and_projects_the_split_settlement() = runTest {
        // 跨规划日:UTC 界线 03:00,02:50 起计的段横跨 03-05/03-06 两个规划日
        val zone = ZoneOffset.UTC
        val ticks = listOf(
            Instant.parse("2026-03-06T02:50:00Z"),
            Instant.parse("2026-03-06T03:10:00Z"),
            Instant.parse("2026-03-06T03:20:00Z"),
        )
        val fixture = stateOf("专注", "写作")
        val focus = fixture.targets[0]
        val writing = fixture.targets[1]
        val executor = ChainingExecutor(fixture, ticks.first(), zone)
        var tick = 0
        val clockSource = ChangeClockSource { ChangeClock(ticks[tick++], zone) }
        val source = WorkspaceSource { WorkspaceView(executor.state, executor.now, zone) }
        val startArgs = buildJsonObject { put("action", "start"); put("targetId", focus.id) }
        val switchArgs = buildJsonObject { put("action", "switch"); put("targetId", writing.id) }
        val stopArgs = buildJsonObject { put("action", "stop") }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("开始给专注计时", ActionRequest("timer_control", startArgs)),
            ReasonerDecision.Act("切到写作", ActionRequest("timer_control", switchArgs)),
            ReasonerDecision.Act("停止计时", ActionRequest("timer_control", stopArgs)),
            ReasonerDecision.Answer(null, "已停止"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(TimerControlTool(source)), executor = executor, clock = clockSource),
        ).run("帮我计一段时")

        // command 序列断言:开始 → 切换 → 停止,各带注入的派发时刻,且 scheduleEntryId 恒 null
        assertEquals("已停止", (outcome as AgentOutcome.Answered).text)
        assertEquals(3, executor.dispatchCount)
        assertEquals(
            listOf(
                AttentionCommand.StartTimer(focus.id),
                AttentionCommand.SwitchTimer(writing.id),
                AttentionCommand.StopTimer,
            ),
            executor.commands,
        )
        assertEquals(ticks, executor.nows)
        assertEquals(listOf(zone, zone, zone), executor.zones)

        // 切换观测:前一段自动结算并按规划日拆成两条(不合并),计时切到新目标
        // (histories[i].last() 是第 i+1 轮推理看到的最后事件,切换观测在第 3 轮历史末尾)
        val switchObservation = (reasoner.histories[2].last() as AgentEvent.Observation).payload
        val settled = executor.state.timeEntries.take(2)
        assertEquals(
            observation(
                running = true,
                paused = false,
                targetTitle = "写作",
                targetId = writing.id,
                elapsedMillis = 0,
                entries = listOf(
                    entry(settled[0].id, "2026-03-05", 10, focus.id, "专注"),
                    entry(settled[1].id, "2026-03-06", 10, focus.id, "专注"),
                ),
            ),
            switchObservation,
        )
        // 停止观测:running:false,本段按规划日结算,总计分钟数供核对
        val stopObservation = (reasoner.histories[3].last() as AgentEvent.Observation).payload
        val lastEntry = executor.state.timeEntries.last()
        assertEquals(
            observation(
                running = false,
                entries = listOf(entry(lastEntry.id, "2026-03-06", 10, writing.id, "写作")),
            ),
            stopObservation,
        )
        // 红线复核:两次结算观测都不含 scheduleEntryId
        assertFalse(switchObservation.containsKey("scheduleEntryId"))
        assertFalse(stopObservation.containsKey("scheduleEntryId"))
    }

    /**
     * 无活动计时器时 stop 为幂等 no-op:StopTimer 照常派发、由领域 no-op 兜底
     * (与「switch 同目标幂等」同构),观测 running:false 即「当前未计时」反馈。
     * spec 明文「幂等 no-op,观测 running:false」;零派发与该观测在三相契约内
     * 不可兼得(零派发只能经解析相抛错产生错误观测),故取派发 no-op 路径。
     */
    @Test
    fun stopping_without_an_active_timer_is_a_domain_no_op_projecting_running_false() = runTest {
        val zone = ZoneOffset.UTC
        val fixture = stateOf("专注")
        val executor = ChainingExecutor(fixture, Instant.parse("2026-03-06T02:00:00Z"), zone)
        val clockSource = ChangeClockSource { ChangeClock(executor.now, zone) }
        val source = WorkspaceSource { WorkspaceView(executor.state, executor.now, zone) }
        val args = buildJsonObject { put("action", "stop") }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("停止计时", ActionRequest("timer_control", args)),
            ReasonerDecision.Answer(null, "当前未计时"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(TimerControlTool(source)), executor = executor, clock = clockSource),
        ).run("停一下计时")

        assertEquals("当前未计时", (outcome as AgentOutcome.Answered).text)
        assertEquals(1, executor.dispatchCount)
        assertEquals(listOf<AttentionCommand>(AttentionCommand.StopTimer), executor.commands)
        // 幂等:状态零变化、零条记录、零里程碑;观测恰为 query_timer 的无计时形状
        assertEquals(fixture, executor.state)
        assertEquals(buildJsonObject { put("running", false) }, (reasoner.histories[1].last() as AgentEvent.Observation).payload)
    }

    @Test
    fun switching_to_the_same_target_is_idempotent_and_adds_no_entries() = runTest {
        val zone = ZoneOffset.UTC
        val fixture = stateOf("专注")
        val focus = fixture.targets[0]
        val executor = ChainingExecutor(
            fixture.startTimer(focus.id, Instant.parse("2026-03-06T02:00:00Z"), zone),
            Instant.parse("2026-03-06T02:15:00Z"),
            zone,
        )
        val clockSource = ChangeClockSource { ChangeClock(executor.now, zone) }
        val source = WorkspaceSource { WorkspaceView(executor.state, executor.now, zone) }
        val args = buildJsonObject { put("action", "switch"); put("targetId", focus.id) }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("切到同一目标", ActionRequest("timer_control", args)),
            ReasonerDecision.Answer(null, "没变"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(TimerControlTool(source)), executor = executor, clock = clockSource),
        ).run("切到专注")

        // 幂等:恰好一次派发,计时器原样(起点不被重置),不产生任何记录
        assertEquals("没变", (outcome as AgentOutcome.Answered).text)
        assertEquals(1, executor.dispatchCount)
        assertEquals(listOf<AttentionCommand>(AttentionCommand.SwitchTimer(focus.id)), executor.commands)
        val timer = executor.state.activeTimer ?: throw AssertionError("计时器不应消失")
        assertEquals(focus.id, timer.targetId)
        assertEquals(Instant.parse("2026-03-06T02:00:00Z").toEpochMilli(), timer.startedAtEpochMillis)
        assertTrue(executor.state.timeEntries.isEmpty())
        // 观测只有计时态,不追加 entries/totalMinutes(零结算)
        val payload = (reasoner.histories[1].last() as AgentEvent.Observation).payload
        assertEquals(
            observation(running = true, paused = false, targetTitle = "专注", targetId = focus.id, elapsedMillis = 900_000),
            payload,
        )
    }

    @Test
    fun an_unknown_target_on_start_becomes_an_error_observation_without_dispatching() = runTest {
        val fixture = stateOf("专注")
        val executor = FakeCommandExecutor()
        val args = buildJsonObject { put("action", "start"); put("targetId", "missing") }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("开始计时", ActionRequest("timer_control", args)),
            ReasonerDecision.Answer(null, "改对了"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(toolOf(fixture)), executor = executor, clock = clock),
        ).run("开始计时")

        // 解析相预校验失败是工具侧失败:错误观测回填自纠,command 零派发
        assertEquals("改对了", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("目标不存在") == true)
        assertEquals(0, executor.dispatchCount)
    }
}

/**
 * 循环级测试专用的真执行通道:把每条 command 链式执行在上一条的结果状态上
 * (生产仓储即如此),并留档派发实况供序列断言;快照源据此给工具提供最新状态。
 */
private class ChainingExecutor(
    initialState: AttentionState,
    initialNow: Instant,
    private val zone: ZoneOffset,
) : CommandExecutor {
    val commands = mutableListOf<AttentionCommand>()
    val nows = mutableListOf<Instant>()
    val zones = mutableListOf<ZoneId>()

    var state: AttentionState = initialState
    var now: Instant = initialNow

    val dispatchCount: Int get() = commands.size

    override suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult {
        commands += command
        nows += now
        zones += zone
        return command.execute(state, now, zone).also {
            state = it.state
            this.now = now
        }
    }
}
