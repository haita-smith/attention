package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.Target
import com.attention.domain.addTimeEntry
import com.attention.domain.editTimeEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * record_time 工具的映射契约:action 判别 → 风险级别 → 领域 command → 观测投影。
 * 表驱动覆盖全部 4 个 action(backfill/assign 自动,edit/delete 需确认);解析相位的
 * 形状校验(日期格式、分钟数>0)与快照存在性预校验(目标/记录 id 必须存在)各有
 * 专项断言;schema 可解析且字段描述齐,edit 的可改字段白名单(日期不可改)由
 * schema 与 command 双重钉住;声明不含 Forbidden,工具面不出现任何平台效果通道。
 */
class RecordTimeToolTest {

    /** 快照底座:一个目标「阅读」。 */
    private fun baseState(): AttentionState =
        AttentionState(targets = listOf(Target("ledger-target", title = "阅读")))

    /** 底座上补三笔记录:未归属、已归属、再一笔未归属,供归属/编辑/删除映射用。 */
    private fun stateWithEntries(): AttentionState =
        baseState()
            .addTimeEntry("2026-03-01", 20, null, note = "忘了计时")
            .addTimeEntry("2026-03-02", 40, "ledger-target")
            .addTimeEntry("2026-03-03", 10, null)

    private fun tool(state: AttentionState = stateWithEntries()): RecordTimeTool =
        RecordTimeTool(WorkspaceSource { WorkspaceView(state, DISPATCH_NOW, DISPATCH_ZONE) })

    private fun AttentionState.unownedIds(): List<String> =
        timeEntries.filter { it.targetId == null }.map { it.id }

    private fun AttentionState.ownedId(): String = timeEntries.first { it.targetId != null }.id

    @Test
    fun every_action_maps_to_its_risk_level_and_domain_command() {
        val state = stateWithEntries()
        val tool = tool(state)
        val unowned = state.unownedIds()
        data class MappingCase(val label: String, val args: JsonObject, val risk: RiskLevel, val command: AttentionCommand)

        listOf(
            MappingCase(
                "补记到目标(带备注)",
                buildJsonObject {
                    put("action", "backfill")
                    put("planningDate", "2026-03-05")
                    put("durationMinutes", 30)
                    put("targetId", "ledger-target")
                    put("note", "昨天忘了计时")
                },
                RiskLevel.Auto,
                AttentionCommand.AddManualTimeEntry("2026-03-05", 30, "ledger-target", "昨天忘了计时"),
            ),
            MappingCase(
                "补记缺省目标即未归属、缺省备注为空",
                buildJsonObject {
                    put("action", "backfill")
                    put("planningDate", "2026-03-06")
                    put("durationMinutes", 15)
                },
                RiskLevel.Auto,
                AttentionCommand.AddManualTimeEntry("2026-03-06", 15, null, ""),
            ),
            MappingCase(
                "未归属归入计划(多条)",
                buildJsonObject {
                    put("action", "assign")
                    putJsonArray("entryIds") {
                        unowned.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }
                    put("targetId", "ledger-target")
                },
                RiskLevel.Auto,
                AttentionCommand.AssignUnowned(unowned.toSet(), "ledger-target"),
            ),
            MappingCase(
                "编辑历史记录(分钟/目标/备注)",
                buildJsonObject {
                    put("action", "edit")
                    put("entryId", state.ownedId())
                    put("durationMinutes", 45)
                    put("targetId", "ledger-target")
                    put("note", "改个备注")
                },
                RiskLevel.Confirmation,
                AttentionCommand.EditTimeEntry(state.ownedId(), 45, "ledger-target", "改个备注"),
            ),
            MappingCase(
                "删除历史记录",
                buildJsonObject {
                    put("action", "delete")
                    put("entryId", state.ownedId())
                },
                RiskLevel.Confirmation,
                AttentionCommand.DeleteTimeEntry(state.ownedId()),
            ),
        ).forEach { case ->
            assertEquals("${case.label} 的风险级别", case.risk, tool.riskLevel(case.args))
            assertEquals("${case.label} 的 command 映射", case.command, tool.command(case.args))
        }
    }

    @Test
    fun every_record_action_projects_flat_entries_and_delete_reports_removed_ones() {
        val tool = tool()

        // backfill:新记录以扁平条目呈现,id/planningDate/durationMinutes/targetId/targetTitle/source/note
        val backfillResult = AttentionCommand.AddManualTimeEntry("2026-03-05", 30, "ledger-target", "昨天忘了计时")
            .execute(baseState(), DISPATCH_NOW, DISPATCH_ZONE)
        val added = backfillResult.timeEntries.single()
        assertEquals(
            buildJsonObject {
                putJsonArray("entries") {
                    add(buildJsonObject {
                        put("id", added.id)
                        put("planningDate", "2026-03-05")
                        put("durationMinutes", 30)
                        put("targetId", "ledger-target")
                        put("targetTitle", "阅读")
                        put("source", "MANUAL")
                        put("note", "昨天忘了计时")
                    })
                }
            },
            tool.observation(buildJsonObject { put("action", "backfill") }, backfillResult),
        )

        // 未归属补记:targetId 缺省不写字段,targetTitle 用引擎标签兜底「未归属活动」,空白备注省略
        val unownedResult = AttentionCommand.AddManualTimeEntry("2026-03-06", 15, null, "")
            .execute(baseState(), DISPATCH_NOW, DISPATCH_ZONE)
        val unowned = unownedResult.timeEntries.single()
        assertEquals(
            buildJsonObject {
                putJsonArray("entries") {
                    add(buildJsonObject {
                        put("id", unowned.id)
                        put("planningDate", "2026-03-06")
                        put("durationMinutes", 15)
                        put("targetTitle", "未归属活动")
                        put("source", "MANUAL")
                    })
                }
            },
            tool.observation(buildJsonObject { put("action", "backfill") }, unownedResult),
        )

        // assign:两条未归属记录归入后各投影一条,条目带上新归属
        val state = stateWithEntries()
        val unownedIds = state.unownedIds()
        val assignResult = AttentionCommand.AssignUnowned(unownedIds.toSet(), "ledger-target")
            .execute(state, DISPATCH_NOW, DISPATCH_ZONE)
        val assignObservation = tool.observation(buildJsonObject { put("action", "assign") }, assignResult)
        val assignedEntries = assignObservation["entries"]!!.jsonArray
        assertEquals(2, assignedEntries.size)
        assignedEntries.forEach { entry ->
            assertEquals("ledger-target", entry.jsonObject["targetId"]?.jsonPrimitive?.content)
            assertEquals("阅读", entry.jsonObject["targetTitle"]?.jsonPrimitive?.content)
        }

        // edit:投影编辑后的记录条目
        val owned = state.ownedId()
        val editResult = AttentionCommand.EditTimeEntry(owned, 45, "ledger-target", "改个备注")
            .execute(state, DISPATCH_NOW, DISPATCH_ZONE)
        assertEquals(
            buildJsonObject {
                putJsonArray("entries") {
                    add(buildJsonObject {
                        put("id", owned)
                        put("planningDate", "2026-03-02")
                        put("durationMinutes", 45)
                        put("targetId", "ledger-target")
                        put("targetTitle", "阅读")
                        put("source", "MANUAL")
                        put("note", "改个备注")
                    })
                }
            },
            tool.observation(buildJsonObject { put("action", "edit") }, editResult),
        )

        // delete:以 removedTimeEntries 回执呈现被删记录,不再有 entries
        val deleteResult = AttentionCommand.DeleteTimeEntry(owned)
            .execute(state, DISPATCH_NOW, DISPATCH_ZONE)
        val removed = deleteResult.removedTimeEntries.single()
        assertEquals(
            buildJsonObject {
                putJsonArray("removedTimeEntries") {
                    add(buildJsonObject {
                        put("id", removed.id)
                        put("planningDate", "2026-03-02")
                        put("durationMinutes", 40)
                        put("targetId", "ledger-target")
                        put("targetTitle", "阅读")
                        put("source", "MANUAL")
                    })
                }
            },
            tool.observation(buildJsonObject { put("action", "delete") }, deleteResult),
        )
    }

    @Test
    fun parameters_schema_round_trips_and_describes_every_field() {
        val schema = Json.decodeFromString<JsonObject>(Json.encodeToString(tool().parametersSchema))

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("action"), schema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        val properties = schema["properties"]?.jsonObject ?: throw AssertionError("schema 缺少 properties")
        properties.forEach { (field, spec) ->
            assertTrue("字段 $field 缺少描述", spec.jsonObject.containsKey("description"))
            assertTrue("字段 $field 缺少类型", spec.jsonObject.containsKey("type"))
        }
        listOf("action", "planningDate", "durationMinutes", "targetId", "note", "entryId", "entryIds")
            .forEach { field -> assertTrue("schema 缺少字段 $field", properties.containsKey(field)) }
        // action 描述列出全部 action:与映射表同一单一事实来源,永不漂移
        val actionDescription = properties["action"]!!.jsonObject["description"]!!.jsonPrimitive.content
        RecordTimeTool.ACTION_RISK.keys.forEach { action ->
            assertTrue("action 描述缺少 $action", actionDescription.contains(action))
        }
        // entryIds 是字符串数组
        assertEquals("array", properties["entryIds"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "string",
            properties["entryIds"]!!.jsonObject["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun the_edit_action_cannot_change_a_records_date() {
        val state = stateWithEntries()
        val tool = tool(state)
        val owned = state.ownedId()

        // 行为锁定:args 即便塞进 planningDate,edit 的 command 也没有日期分量,
        // 执行后记录的规划日保持原值——日期是账本事实,不是工具可改字段
        val args = buildJsonObject {
            put("action", "edit")
            put("entryId", owned)
            put("durationMinutes", 45)
            put("targetId", "ledger-target")
            put("note", "改个备注")
            put("planningDate", "2030-01-01")
        }
        val command = tool.command(args) as AttentionCommand.EditTimeEntry
        val result = command.execute(state, DISPATCH_NOW, DISPATCH_ZONE)
        val edited = result.timeEntries.first { it.id == owned }
        assertEquals("2026-03-02", edited.planningDate)
        assertEquals(45, edited.durationMinutes)

        // schema 锁定:唯一的日期字段 planningDate 只属于 backfill,edit 的参数面没有日期字段
        val properties = tool.parametersSchema["properties"]!!.jsonObject
        val dateFields = properties.keys.filter { it.contains("date", ignoreCase = true) || it.contains("Day") }
        assertEquals(listOf("planningDate"), dateFields)
        val planningDateDescription = properties["planningDate"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue("planningDate 描述应绑定 backfill", planningDateDescription.contains("backfill"))
        assertTrue("planningDate 描述不得提 edit", !planningDateDescription.contains("edit"))
    }

    @Test
    fun edit_without_a_note_argument_passes_through_the_snapshot_note() {
        // 底座上一条带备注的已归属记录、一条无备注的已归属记录
        val state = baseState()
            .addTimeEntry("2026-03-02", 40, "ledger-target", note = "原始备注")
            .addTimeEntry("2026-03-03", 25, "ledger-target")
        val tool = tool(state)
        val notedId = state.timeEntries.first { it.note == "原始备注" }.id
        val unnotedId = state.timeEntries.first { it.targetId != null && it.note.isNullOrBlank() }.id
        fun editArgs(entryId: String, note: String?): JsonObject = buildJsonObject {
            put("action", "edit")
            put("entryId", entryId)
            put("durationMinutes", 45)
            put("targetId", "ledger-target")
            note?.let { put("note", it) }
        }

        // 缺省 note:透传解析相快照中的现值,历史备注不被静默清空(schema 标注 note 可选)
        val keepCommand = tool.command(editArgs(notedId, null)) as AttentionCommand.EditTimeEntry
        assertEquals("原始备注", keepCommand.note)
        val keepResult = keepCommand.execute(state, DISPATCH_NOW, DISPATCH_ZONE)
        assertEquals("原始备注", keepResult.timeEntries.first { it.id == notedId }.note)

        // 显式 note:替换现值,缺省语义不影响显式改写
        val replaceCommand = tool.command(editArgs(notedId, "换了备注")) as AttentionCommand.EditTimeEntry
        assertEquals("换了备注", replaceCommand.note)
        val replaceResult = replaceCommand.execute(state, DISPATCH_NOW, DISPATCH_ZONE)
        assertEquals("换了备注", replaceResult.timeEntries.first { it.id == notedId }.note)

        // 现备注本就为空的记录缺省 edit:按空串透传,与投影「空白省略」同口径
        val unnotedCommand = tool.command(editArgs(unnotedId, null)) as AttentionCommand.EditTimeEntry
        assertEquals("", unnotedCommand.note)
    }

    @Test
    fun the_note_passed_through_on_edit_is_the_parse_phase_snapshot_value() {
        // TOCTOU 语义与既有窗口一致:command 在解析相定格,备注透传读的是与存在性
        // 预校验同一份快照;快照之后另有写入方改写备注,已持有的 command 不受影响
        val state = baseState().addTimeEntry("2026-03-02", 40, "ledger-target", note = "原始备注")
        val tool = tool(state)
        val ownedId = state.timeEntries.single { it.targetId != null }.id
        val command = tool.command(
            buildJsonObject {
                put("action", "edit")
                put("entryId", ownedId)
                put("durationMinutes", 45)
                put("targetId", "ledger-target")
            },
        ) as AttentionCommand.EditTimeEntry

        val mutated = state.editTimeEntry(ownedId, 40, "ledger-target", "旁路改写")
        val result = command.execute(mutated, DISPATCH_NOW, DISPATCH_ZONE)
        assertEquals("原始备注", result.timeEntries.first { it.id == ownedId }.note)
    }

    @Test
    fun unknown_ids_and_invalid_fields_fail_in_the_parse_phase() {
        val state = stateWithEntries()
        val tool = tool(state)
        fun args(action: String? = null, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
            action?.let { put("action", it) }
            apply(build)
        }
        listOf(
            "缺 action" to args { put("planningDate", "2026-03-05"); put("durationMinutes", 30) },
            "未知 action" to args("clear_history"),
            "缺日期" to args("backfill") { put("durationMinutes", 30) },
            "非法日期格式" to args("backfill") { put("planningDate", "2026/03/05"); put("durationMinutes", 30) },
            "缺分钟数" to args("backfill") { put("planningDate", "2026-03-05") },
            "零分钟" to args("backfill") { put("planningDate", "2026-03-05"); put("durationMinutes", 0) },
            "负分钟" to args("backfill") { put("planningDate", "2026-03-05"); put("durationMinutes", -5) },
            "非整数分钟" to args("backfill") { put("planningDate", "2026-03-05"); put("durationMinutes", "半小时") },
            "补记未知目标" to args("backfill") {
                put("planningDate", "2026-03-05"); put("durationMinutes", 30); put("targetId", "stranger")
            },
            "归属未知目标" to args("assign") { putJsonArray("entryIds") {}; put("targetId", "stranger") },
            "归属空记录列表" to args("assign") { putJsonArray("entryIds") {}; put("targetId", "ledger-target") },
            "归属未知记录" to args("assign") {
                putJsonArray("entryIds") { add(kotlinx.serialization.json.JsonPrimitive("stranger")) }
                put("targetId", "ledger-target")
            },
            "编辑未知记录" to args("edit") {
                put("entryId", "stranger"); put("durationMinutes", 45); put("targetId", "ledger-target")
            },
            "编辑未知目标" to args("edit") {
                put("entryId", state.ownedId()); put("durationMinutes", 45); put("targetId", "stranger")
            },
            "编辑缺分钟数" to args("edit") { put("entryId", state.ownedId()); put("targetId", "ledger-target") },
            "编辑零分钟" to args("edit") {
                put("entryId", state.ownedId()); put("durationMinutes", 0); put("targetId", "ledger-target")
            },
            "删除未知记录" to args("delete") { put("entryId", "stranger") },
        ).forEach { (label, caseArgs) ->
            val thrown = assertThrows("$label 应在解析相位失败", IllegalArgumentException::class.java) {
                tool.command(caseArgs)
            }
            assertTrue("$label 的报错应可指导纠正", thrown.message?.isNotBlank() == true)
        }
    }

    @Test
    fun the_action_set_is_exactly_four_record_actions_and_declares_no_forbidden_level() {
        // 字面量清单是独立事实源:动作全集恰好 4 个,补记与归属即时、编辑与删除需确认
        assertEquals(
            mapOf(
                "backfill" to RiskLevel.Auto,
                "assign" to RiskLevel.Auto,
                "edit" to RiskLevel.Confirmation,
                "delete" to RiskLevel.Confirmation,
            ),
            RecordTimeTool.ACTION_RISK,
        )
        assertEquals(setOf(RiskLevel.Auto, RiskLevel.Confirmation), tool().riskLevels)
        assertThrows("未知 action 应在风险判定前被拒", IllegalArgumentException::class.java) {
            tool().riskLevel(buildJsonObject { put("action", "restore_entry") })
        }
        assertThrows("缺 action 应被拒", IllegalArgumentException::class.java) {
            tool().riskLevel(buildJsonObject { })
        }
        // 能注册进目录本身即证明构造期 Forbidden 守卫未被触发
        ToolCatalog(
            changeTools = listOf(tool()),
            executor = FakeCommandExecutor(),
            clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) },
        )
    }

    @Test
    fun the_tool_surface_has_no_platform_effect_channel() {
        // 平台效果边界:工具只持有 WorkspaceSource、只产出领域 command 与投影,
        // 通知、前台服务归 app 层 TimerEffects——工具面不得出现任何平台类型
        val violations = mutableListOf<String>()
        RecordTimeTool::class.java.declaredFields.forEach { field ->
            if (field.type.name.startsWith("android")) violations += "字段 ${field.name}:${field.type.name}"
        }
        RecordTimeTool::class.java.declaredMethods.forEach { method ->
            if (method.returnType.name.startsWith("android")) violations += "方法 ${method.name} 返回 ${method.returnType.name}"
            method.parameterTypes.filter { it.name.startsWith("android") }.forEach {
                violations += "方法 ${method.name} 参数 ${it.name}"
            }
        }
        RecordTimeTool::class.java.constructors.forEach { constructor ->
            constructor.parameterTypes.filter { it.name.startsWith("android") }.forEach {
                violations += "构造参数 ${it.name}"
            }
        }
        assertTrue("工具面出现平台类型:$violations", violations.isEmpty())
    }
}
