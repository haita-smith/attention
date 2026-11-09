package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.addFutureTargetMove
import com.attention.domain.addTarget
import com.attention.domain.archiveTarget
import com.attention.domain.moveTarget
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * manage_target 工具(P1.1 #241)的外部行为:每个 action 的 args → 风险级别、领域
 * command 字段、观测形状三段映射;schema 可解析且字段描述齐;解析相预校验把未知
 * 目标/移动 id、归档态不匹配与参数不合法挡在派发之前。循环级全链路(auto 全链路、
 * 确认流、错误观测零派发、降级开关、无 Forbidden 断言)在同一文件后半部,均在最高
 * 接缝上验证。
 */
class ManageTargetToolTest {
    private val clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) }
    private val planningDay = LocalDate.parse("2026-03-06") // DISPATCH_NOW 按上海时间与界线 03:00 折算

    private fun toolOf(state: AttentionState) =
        ManageTargetTool(WorkspaceSource { WorkspaceView(state, DISPATCH_NOW, DISPATCH_ZONE) })

    private fun stateOf(vararg titles: String): AttentionState = titles.fold(AttentionState()) { state, title -> state.addTarget(title) }

    /**
     * 与工具实现相互独立的条目字面量:观测形状的期望以这里编码的为准。
     * 表内 fixture 均无目标阶段,进度汇总四元读数为零;字段集与 query_targets 对齐。
     */
    private fun entry(id: String, title: String, parentId: String?, archived: Boolean = false): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        parentId?.let { put("parentId", it) }
        put("archived", archived)
        put("targetMinutes", 0)
        put("actualMinutes", 0)
        put("gapMinutes", 0)
        put("completed", false)
    }

    private fun observation(
        vararg targets: JsonObject,
        oldParentId: String? = null,
        targetMove: JsonObject? = null,
        cancelledMoveId: String? = null,
    ): JsonObject =
        buildJsonObject {
            putJsonArray("targets") { targets.forEach { add(it) } }
            oldParentId?.let { put("oldParentId", it) }
            targetMove?.let { put("targetMove", it) }
            cancelledMoveId?.let { put("cancelledMoveId", it) }
        }

    private fun moveEntry(id: String, targetId: String, parentId: String?, effectiveFrom: String): JsonObject = buildJsonObject {
        put("id", id)
        put("targetId", targetId)
        parentId?.let { put("parentId", it) }
        put("effectiveFrom", effectiveFrom)
    }

    /** 一行映射:同一份 args 依次喂给三相,fixture 供预校验与执行后状态推演。 */
    private data class Row(
        val name: String,
        val fixture: AttentionState,
        val args: JsonObject,
        val risk: RiskLevel,
        val command: AttentionCommand,
        val observationOf: (AttentionState) -> JsonObject,
    )

    @Test
    fun every_action_maps_args_to_its_risk_level_command_and_observation() {
        val base = stateOf("工作", "学习")
        val work = base.targets[0]
        val study = base.targets[1]
        val withChild = base.addTarget("孩子", work.id)
        val child = withChild.targets.last()
        val pending = withChild.addFutureTargetMove(child.id, work.id, "2026-03-20", planningDay)
        val moveId = pending.targetMoves.single().id
        val archivedTree = withChild.archiveTarget(work.id).archiveTarget(child.id)

        val rows = listOf(
            Row(
                "create", withChild,
                buildJsonObject { put("action", "create"); put("title", "孩子"); put("parentId", work.id) },
                RiskLevel.Auto,
                AttentionCommand.CreateTarget("孩子", work.id),
                { after -> observation(entry(after.targets.last().id, "孩子", work.id), entry(work.id, "工作", null)) },
            ),
            Row(
                "rename", withChild,
                buildJsonObject { put("action", "rename"); put("targetId", work.id); put("title", "深度工作") },
                RiskLevel.Auto,
                AttentionCommand.RenameTarget(work.id, "深度工作"),
                { observation(entry(work.id, "深度工作", null)) },
            ),
            Row(
                "reorder", withChild,
                buildJsonObject { put("action", "reorder"); put("targetId", study.id); put("newIndex", 0) },
                RiskLevel.Auto,
                AttentionCommand.ReorderTarget(study.id, 0),
                { observation(entry(study.id, "学习", null)) },
            ),
            Row(
                "set_expanded", withChild,
                buildJsonObject { put("action", "set_expanded"); put("targetId", child.id); put("expanded", false) },
                RiskLevel.Auto,
                AttentionCommand.SetTargetExpanded(child.id, false),
                { observation(entry(child.id, "孩子", work.id), entry(work.id, "工作", null)) },
            ),
            Row(
                "move", withChild,
                buildJsonObject { put("action", "move"); put("targetId", child.id); put("newParentId", study.id) },
                RiskLevel.Confirmation,
                AttentionCommand.MoveTarget(child.id, study.id),
                { observation(entry(child.id, "孩子", study.id), entry(study.id, "学习", null), oldParentId = work.id) },
            ),
            Row(
                "schedule_move", withChild,
                buildJsonObject {
                    put("action", "schedule_move"); put("targetId", child.id); put("parentId", study.id); put("effectiveFrom", "2026-03-20")
                },
                RiskLevel.Confirmation,
                AttentionCommand.ScheduleTargetMove(child.id, study.id, "2026-03-20"),
                { after ->
                    observation(
                        entry(child.id, "孩子", work.id),
                        entry(study.id, "学习", null),
                        targetMove = moveEntry(after.targetMoves.last().id, child.id, study.id, "2026-03-20"),
                    )
                },
            ),
            Row(
                "update_move", pending,
                buildJsonObject {
                    put("action", "update_move"); put("moveId", moveId); put("parentId", study.id); put("effectiveFrom", "2026-03-25")
                },
                RiskLevel.Confirmation,
                AttentionCommand.UpdateTargetMove(moveId, study.id, "2026-03-25"),
                { after ->
                    observation(
                        entry(child.id, "孩子", work.id),
                        entry(work.id, "工作", null),
                        targetMove = moveEntry(moveId, child.id, study.id, "2026-03-25"),
                    )
                },
            ),
            Row(
                "cancel_move", pending,
                buildJsonObject { put("action", "cancel_move"); put("moveId", moveId); put("targetId", child.id) },
                RiskLevel.Confirmation,
                AttentionCommand.CancelTargetMove(moveId),
                { observation(entry(child.id, "孩子", work.id), entry(work.id, "工作", null), cancelledMoveId = moveId) },
            ),
            Row(
                "archive", withChild,
                buildJsonObject { put("action", "archive"); put("targetId", child.id) },
                RiskLevel.Confirmation,
                AttentionCommand.ArchiveTarget(child.id),
                { observation(entry(child.id, "孩子", work.id, archived = true), entry(work.id, "工作", null)) },
            ),
            Row(
                "restore", archivedTree,
                buildJsonObject { put("action", "restore"); putJsonArray("targetIds") { add(JsonPrimitive(child.id)) } },
                RiskLevel.Confirmation,
                AttentionCommand.RestoreTargets(setOf(child.id)),
                { observation(entry(child.id, "孩子", work.id), entry(work.id, "工作", null)) },
            ),
        )

        rows.forEach { row ->
            val tool = toolOf(row.fixture)
            assertEquals("${row.name} riskLevel", row.risk, tool.riskLevel(row.args))
            assertEquals("${row.name} command", row.command, tool.command(row.args))
            // 观测相拿的是执行结果(生产上即 executor 的返回),投影只依赖结果状态与 args
            val after = row.command.execute(row.fixture, DISPATCH_NOW, DISPATCH_ZONE).state
            assertEquals("${row.name} observation", row.observationOf(after), tool.observation(row.args, AttentionCommandResult(after)))
        }
    }

    @Test
    fun schema_round_trips_and_describes_the_action_set_and_every_field() {
        val tool = toolOf(AttentionState())
        val schema = Json.decodeFromString<JsonObject>(Json.encodeToString(tool.parametersSchema))

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("action"), schema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        val properties = schema["properties"]?.jsonObject ?: throw AssertionError("schema 缺少 properties")
        properties.forEach { (field, spec) ->
            assertTrue("字段 $field 缺少描述", spec.jsonObject.containsKey("description"))
        }
        listOf("action", "title", "parentId", "targetId", "targetIds", "newIndex", "expanded", "newParentId", "effectiveFrom", "moveId")
            .forEach { field -> assertTrue("schema 缺少字段 $field", properties.containsKey(field)) }
        // action 描述列出全部 action:与映射表同一单一事实来源,永不漂移
        val actionDescription = properties["action"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: ""
        ManageTargetTool.ACTION_RISK.keys.forEach { action ->
            assertTrue("action 描述缺少 $action", actionDescription.contains(action))
        }
    }

    @Test
    fun the_action_set_is_exactly_the_ten_management_actions_without_deletion() {
        val expected = setOf(
            "create", "rename", "reorder", "set_expanded",
            "move", "schedule_move", "update_move", "cancel_move", "archive", "restore",
        )
        // 永禁红线:action 全集恰为这 10 个管理动作,不含任何删除类动作
        assertEquals(expected, ManageTargetTool.ACTION_RISK.keys)
        assertTrue(ManageTargetTool.ACTION_RISK.keys.none { it.contains("delete") || it.contains("remove") || it.contains("clear") })
        // 声明只含 Auto 与 Confirmation,Forbidden 不进声明;目录注册因此放行
        assertEquals(setOf(RiskLevel.Auto, RiskLevel.Confirmation), toolOf(AttentionState()).riskLevels)
        assertThrows(IllegalArgumentException::class.java) {
            toolOf(AttentionState()).riskLevel(buildJsonObject { put("action", "delete_relations") })
        }
    }

    @Test
    fun the_tool_registers_into_a_catalog_without_any_forbidden_level() {
        val catalog = ToolCatalog(
            changeTools = listOf(toolOf(AttentionState())),
            executor = FakeCommandExecutor(),
            clock = clock,
        )
        assertTrue(catalog.changeTools.single().riskLevels.none { it == RiskLevel.Forbidden })
    }

    @Test
    fun parse_phase_rejects_malformed_arguments_before_any_dispatch() {
        val fixture = stateOf("工作")
        val tool = toolOf(fixture)
        val work = fixture.targets.single()
        val withMove = fixture.addFutureTargetMove(work.id, null, "2026-03-20", planningDay)
        val moveId = withMove.targetMoves.single().id

        assertThrows("缺 action", IllegalArgumentException::class.java) { tool.riskLevel(buildJsonObject { put("title", "无动作") }) }
        assertThrows("未知 action", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "delete"); put("targetId", work.id) })
        }
        assertThrows("缺必填字段", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "rename"); put("targetId", work.id) })
        }
        assertThrows("日期格式", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "schedule_move"); put("targetId", work.id); put("effectiveFrom", "2026/03/20") })
        }
        assertThrows("非整数", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "reorder"); put("targetId", work.id); put("newIndex", "第一") })
        }
        assertThrows("非布尔", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "set_expanded"); put("targetId", work.id); put("expanded", "偶尔") })
        }
        assertThrows("空列表", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "restore"); putJsonArray("targetIds") {} })
        }
        assertThrows("未知移动", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "update_move"); put("moveId", "missing"); put("effectiveFrom", "2026-03-25") })
        }
        assertThrows("移动与目标不符", IllegalArgumentException::class.java) {
            tool.command(buildJsonObject { put("action", "cancel_move"); put("moveId", moveId); put("targetId", "另一个") })
        }
    }

    @Test
    fun an_unknown_target_on_an_auto_action_becomes_an_error_observation_without_dispatching() = runTest {
        val fixture = stateOf("工作")
        val args = buildJsonObject { put("action", "rename"); put("targetId", "missing"); put("title", "任意") }
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("重命名", ActionRequest("manage_target", args)),
            ReasonerDecision.Answer(null, "改对了"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(toolOf(fixture)), executor = executor, clock = clock),
        ).run("改个名")

        // 解析相预校验失败是工具侧失败:错误观测回填自纠,command 零派发
        assertEquals("改对了", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("目标不存在") == true)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun an_unknown_target_on_a_confirmation_action_mints_no_token_and_dispatches_nothing() = runTest {
        val fixture = stateOf("工作")
        val args = buildJsonObject { put("action", "archive"); put("targetId", "missing") }
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("归档", ActionRequest("manage_target", args)),
            ReasonerDecision.Answer(null, "知道了"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(toolOf(fixture)), executor = executor, clock = clock, gate = gate),
        ).run("归档它")

        // 预校验在铸令牌之前:不产生待确认短路,错误观测回填,零派发
        assertTrue(outcome is AgentOutcome.Answered)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun malformed_arguments_become_an_error_observation_without_dispatching() = runTest {
        val fixture = stateOf("工作")
        val args = buildJsonObject { put("action", "rename"); put("targetId", fixture.targets.single().id) }
        val executor = FakeCommandExecutor()
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("重命名", ActionRequest("manage_target", args)),
            ReasonerDecision.Answer(null, "补上了"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(toolOf(fixture)), executor = executor, clock = clock),
        ).run("改个名")

        assertEquals("补上了", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("title") == true)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun an_auto_action_runs_the_full_chain_then_loops_to_an_answer() = runTest {
        val fixture = stateOf("工作")
        val work = fixture.targets.single()
        val args = buildJsonObject {
            put("action", "create"); put("title", "孩子"); put("parentId", work.id)
        }
        // 生产上 executor 返回落库后的状态;替身按同一契约返回执行后的状态
        val executed = fixture.addTarget("孩子", work.id)
        val executor = FakeCommandExecutor(result = AttentionCommandResult(executed))
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("建一个子目标", ActionRequest("manage_target", args)),
            ReasonerDecision.Answer(null, "已创建"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(toolOf(fixture)), executor = executor, clock = clock),
        ).run("帮我建个子目标")

        // 全链路:行动 → 恰好一次派发(正确的 command 与注入的 now/zone)→ 观测回填 → 答复
        assertEquals("已创建", (outcome as AgentOutcome.Answered).text)
        assertEquals(1, executor.dispatchCount)
        val command = executor.commands.single() as AttentionCommand.CreateTarget
        assertEquals("孩子", command.title)
        assertEquals(fixture.targets.single().id, command.parentId)
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertFalse(observation.isError)
        val targets = observation.payload["targets"]?.jsonArray ?: throw AssertionError("观测缺少 targets")
        assertEquals("孩子", targets.first().jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun a_confirmation_action_short_circuits_then_dispatches_exactly_once_after_confirm() = runTest {
        val fixture = stateOf("工作")
        val targetId = fixture.targets.single().id
        val args = buildJsonObject { put("action", "archive"); put("targetId", targetId) }
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(ReasonerDecision.Act("请求归档", ActionRequest("manage_target", args)))

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(toolOf(fixture)), executor = executor, clock = clock, gate = gate),
        ).run("归档它")

        // 短路即收敛:不执行、不回填观测,以待确认终态交出令牌
        val pending = outcome as AgentOutcome.PendingConfirmation
        assertEquals(0, executor.dispatchCount)
        // 确认后经同一 executor 恰好派发一次短路时持有的 command
        assertEquals(ConfirmationResolution.Executed(DISPATCHED_RESULT), gate.confirm(pending.token))
        assertEquals(1, executor.dispatchCount)
        assertEquals(AttentionCommand.ArchiveTarget(targetId), executor.commands.single())
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
    }

    @Test
    fun a_cancelled_confirmation_action_never_dispatches() = runTest {
        val fixture = stateOf("工作")
        val args = buildJsonObject { put("action", "archive"); put("targetId", fixture.targets.single().id) }
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(ReasonerDecision.Act("请求归档", ActionRequest("manage_target", args)))

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(changeTools = listOf(toolOf(fixture)), executor = executor, clock = clock, gate = gate),
        ).run("归档它")

        // 拒绝后数据保持原样:零派发,令牌一次性消费
        val pending = outcome as AgentOutcome.PendingConfirmation
        assertEquals(ConfirmationResolution.Cancelled, gate.cancel(pending.token))
        assertEquals(0, executor.dispatchCount)
        assertEquals(ConfirmationResolution.AlreadyResolved, gate.confirm(pending.token))
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun archive_and_restore_prevalidate_the_archived_state_in_the_parse_phase() {
        val base = stateOf("工作")
        val work = base.targets.single()
        val archived = base.archiveTarget(work.id)

        assertThrows("归档已归档目标应在解析相位失败", IllegalArgumentException::class.java) {
            toolOf(archived).command(buildJsonObject { put("action", "archive"); put("targetId", work.id) })
        }
        assertThrows("恢复未归档目标应在解析相位失败", IllegalArgumentException::class.java) {
            toolOf(base).command(buildJsonObject { put("action", "restore"); putJsonArray("targetIds") { add(JsonPrimitive(work.id)) } })
        }
    }

    @Test
    fun move_observation_reports_the_old_parent_from_the_parse_phase_snapshot() {
        val base = stateOf("工作", "学习")
        val work = base.targets[0]
        val study = base.targets[1]
        val withChild = base.addTarget("孩子", work.id)
        val child = withChild.targets.last()
        var current = withChild
        val tool = ManageTargetTool(WorkspaceSource { WorkspaceView(current, DISPATCH_NOW, DISPATCH_ZONE) })
        val args = buildJsonObject { put("action", "move"); put("targetId", child.id); put("newParentId", study.id) }
        tool.command(args)

        // 观测相的快照已是换父后的世界:旧父在那里已查不到,回执仍取解析相的派发前快照
        current = current.moveTarget(child.id, study.id)
        val observation = tool.observation(args, AttentionCommandResult(current))

        assertEquals(work.id, observation["oldParentId"]?.jsonPrimitive?.content)
    }

    @Test
    fun a_confirmation_action_with_the_switch_off_becomes_an_error_observation_without_a_token_or_dispatch() = runTest {
        val fixture = stateOf("工作")
        val args = buildJsonObject { put("action", "archive"); put("targetId", fixture.targets.single().id) }
        val executor = FakeCommandExecutor()
        val gate = ConfirmationGate(executor, clock)
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求归档", ActionRequest("manage_target", args)),
            ReasonerDecision.Answer(null, "需要确认的操作暂时不可用"),
        )

        val outcome = ReActAgent(
            reasoner,
            ToolCatalog(
                changeTools = listOf(toolOf(fixture)),
                executor = executor,
                clock = clock,
                gate = gate,
                confirmationEnabled = false,
            ),
        ).run("归档它")

        // 降级开关关闭:确认级动作整体不可达——错误观测回填,不铸令牌、不派发,绝不静默转自动
        assertEquals("需要确认的操作暂时不可用", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("需确认操作暂不可用") == true)
        assertEquals(0, executor.dispatchCount)
    }
}
