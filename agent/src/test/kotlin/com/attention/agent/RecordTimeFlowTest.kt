package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionState
import com.attention.domain.Target
import com.attention.domain.addTimeEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * record_time 工具在最高接缝(ReActAgent.run)上的全链路语义:
 * 自动级 action(backfill/assign)恰好派发一次正确的 command 与注入的 now/zone、
 * 观测回填、循环继续至答复;确认级 action(edit/delete)请求即短路,确认后经同一
 * 执行通道恰好派发一次,拒绝则零派发且令牌不可重放;未知记录/目标 id 与非法日期、
 * 分钟数在解析相位转为错误观测回填,零派发、不铸令牌;降级开关关闭时确认级整体
 * 不可达。回执断言只走执行通道:dispatchCount/commands/nows/zones 是工具的全部
 * 副作用面,不存在任何平台效果旁路。
 */
class RecordTimeFlowTest {

    // 时钟与快照同一套事实(ADR-0005):上海时间 2026-03-06 10:00,规划日 2026-03-06
    private val clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) }

    private fun baseState() = AttentionState(targets = listOf(Target("flow-target", title = "阅读")))

    /** 底座上补三笔记录:两笔未归属、一笔已归属,供归属/编辑/删除链路用。 */
    private fun stateWithEntries() = baseState()
        .addTimeEntry("2026-03-01", 20, null, note = "忘了计时")
        .addTimeEntry("2026-03-02", 40, "flow-target")
        .addTimeEntry("2026-03-03", 10, null)

    private fun sourceOf(state: AttentionState) = WorkspaceSource { WorkspaceView(state, DISPATCH_NOW, DISPATCH_ZONE) }

    private fun catalog(tool: ChangeTool, executor: CommandExecutor, gate: ConfirmationGate? = null, confirmationEnabled: Boolean = true) =
        ToolCatalog(changeTools = listOf(tool), executor = executor, clock = clock, gate = gate, confirmationEnabled = confirmationEnabled)

    @Test
    fun auto_backfill_without_a_target_records_unowned_time_and_reports_the_entry() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val tool = RecordTimeTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "backfill")
            put("planningDate", "2026-03-06")
            put("durationMinutes", 30)
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("补记半小时忘了计时的工作", ActionRequest("record_time", args)),
            ReasonerDecision.Answer(null, "已补记 30 分钟"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("帮我补记半小时,忘了开计时")

        // 全链路:行动 → 派发 → 观测回填 → 答复;targetId 缺省即未归属,备注缺省为空
        assertEquals(1, executor.dispatchCount)
        assertEquals(
            listOf<AttentionCommand>(AttentionCommand.AddManualTimeEntry("2026-03-06", 30, null, "")),
            executor.commands,
        )
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
        val answered = outcome as AgentOutcome.Answered
        assertEquals("已补记 30 分钟", answered.text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertFalse(observation.isError)
        assertEquals(
            listOf(
                AgentEvent.Thought("补记半小时忘了计时的工作"),
                AgentEvent.Action("record_time", args),
                observation,
                AgentEvent.Final("已补记 30 分钟"),
            ),
            answered.trace,
        )
        // 未归属记录的观测:targetTitle 用引擎标签兜底,targetId 与空白备注缺省不写
        val entry = observation.payload["entries"]!!.jsonArray.single().jsonObject
        assertEquals("2026-03-06", entry["planningDate"]?.jsonPrimitive?.content)
        assertEquals(30, entry["durationMinutes"]?.jsonPrimitive?.content?.toInt())
        assertEquals("未归属活动", entry["targetTitle"]?.jsonPrimitive?.content)
        assertEquals("MANUAL", entry["source"]?.jsonPrimitive?.content)
        assertTrue("条目应带记录 id", entry["id"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertFalse("未归属记录不写 targetId", "targetId" in entry.keys)
        assertFalse("空白备注缺省不写", "note" in entry.keys)
    }

    @Test
    fun auto_assign_moves_unowned_records_into_a_target_and_reports_each_one() = runTest {
        val state = stateWithEntries()
        val unownedIds = state.timeEntries.filter { it.targetId == null }.map { it.id }
        val executor = ExecutingExecutor(state)
        val tool = RecordTimeTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "assign")
            putJsonArray("entryIds") { unownedIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("targetId", "flow-target")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("把未归属记录归入阅读", ActionRequest("record_time", args)),
            ReasonerDecision.Answer(null, "已归入阅读"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor)).run("把那几条未归属的记到阅读下面")

        // 恰好一次派发:command 是全部未归属记录的整体归属(全有或全无)
        assertEquals("已归入阅读", (outcome as AgentOutcome.Answered).text)
        assertEquals(1, executor.dispatchCount)
        assertEquals(
            listOf(AttentionCommand.AssignUnowned(unownedIds.toSet(), "flow-target")),
            executor.commands,
        )
        // 观测逐条呈现归入后的记录,条目带上新归属
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertFalse(observation.isError)
        val entries = observation.payload["entries"]!!.jsonArray
        assertEquals(2, entries.size)
        entries.forEach { entry ->
            assertEquals("flow-target", entry.jsonObject["targetId"]?.jsonPrimitive?.content)
            assertEquals("阅读", entry.jsonObject["targetTitle"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun edit_short_circuits_and_confirming_dispatches_the_held_command_once() = runTest {
        val state = stateWithEntries()
        val ownedId = state.timeEntries.first { it.targetId != null }.id
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = RecordTimeTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "edit")
            put("entryId", ownedId)
            put("durationMinutes", 45)
            put("targetId", "flow-target")
            put("note", "改个备注")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求编辑历史记录", ActionRequest("record_time", args)),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor, gate)).run("把那条 40 分钟的改成 45 分钟")

        // 请求即短路:不派发、不回填观测、不消耗后续步数
        val pending = outcome as AgentOutcome.PendingConfirmation
        assertEquals(0, executor.dispatchCount)
        assertEquals(1, reasoner.histories.size)
        assertEquals(
            listOf(
                AgentEvent.Thought("请求编辑历史记录"),
                AgentEvent.Action("record_time", args),
                AgentEvent.PendingConfirmation("record_time", args, pending.token),
            ),
            pending.trace,
        )
        assertEquals(
            buildJsonObject { put("tool", "record_time"); put("args", args) },
            pending.preview,
        )

        // 确认后经同一执行通道恰好派发一次,command 是短路时解析持有的那条
        assertTrue(gate.confirm(pending.token) is ConfirmationResolution.Executed)
        assertEquals(1, executor.dispatchCount)
        assertEquals(
            listOf(AttentionCommand.EditTimeEntry(ownedId, 45, "flow-target", "改个备注")),
            executor.commands,
        )
        assertEquals(listOf(DISPATCH_NOW), executor.nows)
        assertEquals(listOf(DISPATCH_ZONE), executor.zones)
    }

    @Test
    fun delete_confirm_dispatches_once_and_returns_the_removed_record() = runTest {
        val state = stateWithEntries()
        val ownedId = state.timeEntries.first { it.targetId != null }.id
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = RecordTimeTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "delete")
            put("entryId", ownedId)
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求删除历史记录", ActionRequest("record_time", args)),
        )

        val pending = ReActAgent(reasoner, catalog(tool, executor, gate)).run("删掉那条 40 分钟的记录")
            as AgentOutcome.PendingConfirmation

        // 确认后恰好派发一次;删除的回执是被删记录本身(removedTimeEntries)
        val resolved = gate.confirm(pending.token) as ConfirmationResolution.Executed
        assertEquals(1, executor.dispatchCount)
        assertEquals(listOf(AttentionCommand.DeleteTimeEntry(ownedId)), executor.commands)
        assertEquals(ownedId, resolved.result.removedTimeEntries.single().id)
    }

    @Test
    fun delete_refusal_dispatches_nothing_and_the_token_cannot_be_replayed() = runTest {
        val state = stateWithEntries()
        val ownedId = state.timeEntries.first { it.targetId != null }.id
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = RecordTimeTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "delete")
            put("entryId", ownedId)
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("请求删除历史记录", ActionRequest("record_time", args)),
        )

        val pending = ReActAgent(reasoner, catalog(tool, executor, gate)).run("删掉那条 40 分钟的记录")
            as AgentOutcome.PendingConfirmation

        // 拒绝即裁决:executor 零派发,账本保持原样;同一令牌再确认不得派发
        assertEquals(ConfirmationResolution.Cancelled, gate.cancel(pending.token))
        assertEquals(0, executor.dispatchCount)
        assertEquals(ConfirmationResolution.AlreadyResolved, gate.confirm(pending.token))
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun an_unknown_record_id_on_a_confirmation_action_becomes_an_error_observation_without_a_token_or_dispatch() = runTest {
        val state = stateWithEntries()
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = RecordTimeTool(sourceOf(state))
        val args = buildJsonObject {
            put("action", "edit")
            put("entryId", "stranger")
            put("durationMinutes", 45)
            put("targetId", "flow-target")
        }
        val reasoner = FakeReasoner(
            ReasonerDecision.Act("凭记忆编辑记录", ActionRequest("record_time", args)),
            ReasonerDecision.Answer(null, "没有找到那条记录"),
        )

        val outcome = ReActAgent(reasoner, catalog(tool, executor, gate)).run("把那条记录改成 45 分钟")

        // 解析相位预校验失败:错误观测回填自纠,确认级动作也不为无效请求铸令牌
        assertEquals("没有找到那条记录", (outcome as AgentOutcome.Answered).text)
        val observation = reasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(observation.isError)
        assertTrue(observation.payload["error"]?.jsonPrimitive?.content?.contains("时间记录不存在") == true)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun invalid_dates_and_minutes_become_error_observations_without_dispatching() = runTest {
        val state = baseState()
        val executor = ExecutingExecutor(state)
        val tool = RecordTimeTool(sourceOf(state))
        val badMinutes = buildJsonObject {
            put("action", "backfill")
            put("planningDate", "2026-03-06")
            put("durationMinutes", 0)
        }
        val badDate = buildJsonObject {
            put("action", "backfill")
            put("planningDate", "2026/03/06")
            put("durationMinutes", 30)
        }
        val badMinutesReasoner = FakeReasoner(
            ReasonerDecision.Act("补记零分钟", ActionRequest("record_time", badMinutes)),
            ReasonerDecision.Answer(null, "分钟数得是正数"),
        )
        val badDateReasoner = FakeReasoner(
            ReasonerDecision.Act("换个说法补记", ActionRequest("record_time", badDate)),
            ReasonerDecision.Answer(null, "日期格式不对"),
        )

        val firstOutcome = ReActAgent(badMinutesReasoner, catalog(tool, executor)).run("帮我补记一下")
        val secondOutcome = ReActAgent(badDateReasoner, catalog(tool, executor)).run("再补记一次")

        // 两次形状错误都是工具侧失败:错误观测逐次回填,自动级同样零派发
        assertEquals("分钟数得是正数", (firstOutcome as AgentOutcome.Answered).text)
        assertEquals("日期格式不对", (secondOutcome as AgentOutcome.Answered).text)
        val firstObservation = badMinutesReasoner.histories[1].last() as AgentEvent.Observation
        val secondObservation = badDateReasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(firstObservation.isError)
        assertTrue(firstObservation.payload["error"]?.jsonPrimitive?.content?.contains("正整数") == true)
        assertTrue(secondObservation.isError)
        assertTrue(secondObservation.payload["error"]?.jsonPrimitive?.content?.contains("YYYY-MM-DD") == true)
        assertEquals(0, executor.dispatchCount)
    }

    @Test
    fun edit_and_delete_are_unreachable_when_the_confirmation_switch_is_off() = runTest {
        val state = stateWithEntries()
        val ownedId = state.timeEntries.first { it.targetId != null }.id
        val executor = ExecutingExecutor(state)
        val gate = ConfirmationGate(executor, clock)
        val tool = RecordTimeTool(sourceOf(state))
        val editReasoner = FakeReasoner(
            ReasonerDecision.Act("请求编辑历史记录", ActionRequest("record_time", buildJsonObject {
                put("action", "edit")
                put("entryId", ownedId)
                put("durationMinutes", 45)
                put("targetId", "flow-target")
            })),
            ReasonerDecision.Answer(null, "需要确认的操作暂时不可用"),
        )
        val deleteReasoner = FakeReasoner(
            ReasonerDecision.Act("请求删除历史记录", ActionRequest("record_time", buildJsonObject {
                put("action", "delete")
                put("entryId", ownedId)
            })),
            ReasonerDecision.Answer(null, "需要确认的操作暂时不可用"),
        )

        val editOutcome = ReActAgent(
            editReasoner,
            catalog(tool, executor, gate, confirmationEnabled = false),
        ).run("把那条记录改成 45 分钟")
        val deleteOutcome = ReActAgent(
            deleteReasoner,
            catalog(tool, executor, gate, confirmationEnabled = false),
        ).run("然后把那条记录删掉")

        // 降级开关关闭:edit 与 delete 整体不可达——错误观测回填,不铸令牌、不派发,
        // 绝不静默转自动;两次请求各收到一次不可用说明
        assertEquals("需要确认的操作暂时不可用", (editOutcome as AgentOutcome.Answered).text)
        assertEquals("需要确认的操作暂时不可用", (deleteOutcome as AgentOutcome.Answered).text)
        val editObservation = editReasoner.histories[1].last() as AgentEvent.Observation
        val deleteObservation = deleteReasoner.histories[1].last() as AgentEvent.Observation
        assertTrue(editObservation.isError)
        assertTrue(editObservation.payload["error"]?.jsonPrimitive?.content?.contains("需确认操作暂不可用") == true)
        assertTrue(deleteObservation.isError)
        assertTrue(deleteObservation.payload["error"]?.jsonPrimitive?.content?.contains("需确认操作暂不可用") == true)
        assertEquals(0, executor.dispatchCount)
    }
}
