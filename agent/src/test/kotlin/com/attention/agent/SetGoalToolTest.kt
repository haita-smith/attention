package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.FutureGoalRule
import com.attention.domain.GoalCadence
import com.attention.domain.GoalStage
import com.attention.domain.Migration
import com.attention.domain.Target
import java.time.Instant
import java.time.ZoneOffset
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
 * set_goal 工具的映射契约:action 判别 → 风险级别 → 领域 command → 观测投影。
 * 表驱动覆盖全部 8 个 action;解析相位的预校验(未知目标/阶段/规则/迁移 id、
 * 非法周期与日期)与永禁红线(无删除类 action、不声明 Forbidden)各有专项断言。
 */
class SetGoalToolTest {
    private val viewNow = Instant.parse("2026-10-08T12:00:00Z")
    private val viewZone = ZoneOffset.UTC

    /** 快照底座:一个带一次性阶段(目标 30、未投入)的目标。 */
    private fun baseState(): AttentionState {
        val target = Target("goal-target", title = "阅读")
        val stage = GoalStage("goal-stage", target.id, GoalCadence.ONE_TIME, 30, "2026-10-01")
        return AttentionState(targets = listOf(target), goalStages = listOf(stage))
    }

    /** 在底座上补一条待生效未来规则与一笔活动迁移,供改停类 action 映射与预校验用。 */
    private fun stateWithRuleAndMigration(): AttentionState {
        val rule = FutureGoalRule(
            id = "goal-rule",
            targetId = "goal-target",
            stageId = "goal-stage",
            cadence = GoalCadence.WEEKLY,
            targetMinutes = 45,
            effectiveFrom = "2026-10-09",
        )
        val migration = Migration(
            id = "goal-migration",
            targetId = "goal-target",
            sourceStageId = "goal-stage",
            minutes = 5,
            destinationStartDate = "2026-10-09",
        )
        return baseState().copy(futureGoalRules = listOf(rule), migrations = listOf(migration))
    }

    private fun tool(state: AttentionState = baseState()): SetGoalTool =
        SetGoalTool(WorkspaceSource { WorkspaceView(state, viewNow, viewZone) })

    private data class MappingCase(
        val action: String,
        val args: JsonObject,
        val risk: RiskLevel,
        val verify: (AttentionCommand) -> Unit,
    )

    @Test
    fun every_action_maps_to_its_risk_level_and_domain_command() {
        val tool = tool(stateWithRuleAndMigration())
        val cases = listOf(
            MappingCase(
                action = "add_stage",
                args = buildJsonObject {
                    put("action", "add_stage")
                    put("targetId", "goal-target")
                    put("cadence", "DAILY")
                    put("targetMinutes", 30)
                    put("startDate", "2026-10-08")
                    put("dueDate", "2026-10-20")
                },
                risk = RiskLevel.Auto,
                verify = { parsed ->
                    assertEquals(
                        AttentionCommand.AddGoalStage("goal-target", GoalCadence.DAILY, 30, "2026-10-08", "2026-10-20"),
                        parsed,
                    )
                },
            ),
            MappingCase(
                action = "append_one_time_stage",
                args = buildJsonObject {
                    put("action", "append_one_time_stage")
                    put("targetId", "goal-target")
                    put("targetMinutes", 20)
                },
                risk = RiskLevel.Auto,
                verify = { parsed ->
                    assertEquals(AttentionCommand.AppendOneTimeGoalStage("goal-target", 20), parsed)
                },
            ),
            MappingCase(
                action = "add_future_rule",
                args = buildJsonObject {
                    put("action", "add_future_rule")
                    put("targetId", "goal-target")
                    put("cadence", "WEEKLY")
                    put("targetMinutes", 45)
                    put("effectiveFrom", "2026-10-09")
                    put("stageId", "goal-stage")
                    put("dueDate", "2026-10-16")
                },
                risk = RiskLevel.Confirmation,
                verify = { parsed ->
                    val rule = (parsed as AttentionCommand.AddFutureGoalRule).rule
                    assertEquals(
                        FutureGoalRule(
                            id = rule.id,
                            targetId = "goal-target",
                            stageId = "goal-stage",
                            cadence = GoalCadence.WEEKLY,
                            targetMinutes = 45,
                            effectiveFrom = "2026-10-09",
                            dueDate = "2026-10-16",
                        ),
                        rule,
                    )
                },
            ),
            MappingCase(
                action = "update_future_rule",
                args = buildJsonObject {
                    put("action", "update_future_rule")
                    put("targetId", "goal-target")
                    put("ruleId", "goal-rule")
                    put("cadence", "DAILY")
                    put("targetMinutes", 50)
                    put("effectiveFrom", "2026-10-10")
                },
                risk = RiskLevel.Confirmation,
                verify = { parsed ->
                    assertEquals(
                        AttentionCommand.UpdateFutureGoalRule(
                            FutureGoalRule(
                                id = "goal-rule",
                                targetId = "goal-target",
                                cadence = GoalCadence.DAILY,
                                targetMinutes = 50,
                                effectiveFrom = "2026-10-10",
                            ),
                        ),
                        parsed,
                    )
                },
            ),
            MappingCase(
                action = "cancel_future_rule",
                args = buildJsonObject {
                    put("action", "cancel_future_rule")
                    put("targetId", "goal-target")
                    put("ruleId", "goal-rule")
                },
                risk = RiskLevel.Confirmation,
                verify = { parsed ->
                    assertEquals(AttentionCommand.CancelFutureGoalRule("goal-rule"), parsed)
                },
            ),
            MappingCase(
                action = "add_migration",
                args = buildJsonObject {
                    put("action", "add_migration")
                    put("targetId", "goal-target")
                    put("sourceStageId", "goal-stage")
                    put("minutes", 10)
                    put("destinationStartDate", "2026-10-09")
                    put("destinationEndDate", "2026-10-15")
                },
                risk = RiskLevel.Confirmation,
                verify = { parsed ->
                    val migration = (parsed as AttentionCommand.AddMigration).migration
                    assertEquals(
                        Migration(
                            id = migration.id,
                            targetId = "goal-target",
                            sourceStageId = "goal-stage",
                            minutes = 10,
                            destinationStartDate = "2026-10-09",
                            destinationEndDate = "2026-10-15",
                        ),
                        migration,
                    )
                },
            ),
            MappingCase(
                action = "update_migration",
                args = buildJsonObject {
                    put("action", "update_migration")
                    put("targetId", "goal-target")
                    put("migrationId", "goal-migration")
                    put("sourceStageId", "goal-stage")
                    put("minutes", 8)
                    put("destinationStartDate", "2026-10-09")
                },
                risk = RiskLevel.Confirmation,
                verify = { parsed ->
                    assertEquals(
                        AttentionCommand.UpdateMigration(
                            Migration(
                                id = "goal-migration",
                                targetId = "goal-target",
                                sourceStageId = "goal-stage",
                                minutes = 8,
                                destinationStartDate = "2026-10-09",
                            ),
                        ),
                        parsed,
                    )
                },
            ),
            MappingCase(
                action = "cancel_migration",
                args = buildJsonObject {
                    put("action", "cancel_migration")
                    put("targetId", "goal-target")
                    put("migrationId", "goal-migration")
                },
                risk = RiskLevel.Confirmation,
                verify = { parsed ->
                    assertEquals(AttentionCommand.CancelMigration("goal-migration"), parsed)
                },
            ),
        )

        cases.forEach { case ->
            assertEquals("${case.action} 的风险级别", case.risk, tool.riskLevel(case.args))
            case.verify(tool.command(case.args))
        }
    }

    @Test
    fun every_action_projects_the_affected_target_entry_like_query_targets() {
        val tool = tool()
        // 观测对齐 query_targets 先例:扁平目标条目 + 进度汇总,null 字段(parentId)不写,
        // 只含受影响目标,不回显全量状态
        val expected = buildJsonObject {
            putJsonArray("targets") {
                add(buildJsonObject {
                    put("id", "goal-target")
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
        listOf(
            "add_stage",
            "append_one_time_stage",
            "add_future_rule",
            "update_future_rule",
            "cancel_future_rule",
            "add_migration",
            "update_migration",
            "cancel_migration",
        ).forEach { action ->
            val args = buildJsonObject { put("action", action); put("targetId", "goal-target") }
            assertEquals("$action 的观测形状", expected, tool.observation(args, AttentionCommandResult(baseState())))
        }
    }

    @Test
    fun parameters_schema_describes_the_action_discriminator_and_every_field() {
        val schema = tool().parametersSchema

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("action 应为必填", "action" in required)
        assertTrue("targetId 应为必填", "targetId" in required)
        val properties = schema["properties"]!!.jsonObject
        // 每个动作要用的参数字段都有类型与描述,下一阶段 LLM 可直接把 schema 绑定进 prompt
        listOf(
            "action", "targetId", "cadence", "targetMinutes", "startDate", "dueDate", "stageId",
            "effectiveFrom", "ruleId", "sourceStageId", "minutes", "destinationStartDate",
            "destinationEndDate", "migrationId",
        ).forEach { key ->
            val property = properties[key] ?: throw AssertionError("schema 缺少字段描述:$key")
            assertTrue("字段 $key 缺少类型", property.jsonObject.containsKey("type"))
            assertTrue("字段 $key 缺少描述", property.jsonObject.containsKey("description"))
        }
        // 周期取值描述与领域枚举共用一套事实,永不漂移
        val cadenceDescription = properties["cadence"]!!.jsonObject["description"]!!.jsonPrimitive.content
        GoalCadence.entries.forEach { cadence -> assertTrue(cadenceDescription.contains(cadence.name)) }
    }

    @Test
    fun unknown_ids_and_invalid_fields_fail_in_the_parse_phase() {
        // 第二个目标只用于「不属于」类断言
        val twoTargets = baseState().copy(targets = baseState().targets + Target("other-target", title = "另一目标"))
        val tool = tool(stateWithRuleAndMigration().copy(targets = twoTargets.targets))
        fun args(action: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
            put("action", action)
            put("targetId", "goal-target")
            apply(build)
        }
        val cases = listOf(
            "未知目标" to args("add_stage") {
                put("cadence", "DAILY"); put("targetMinutes", 30); put("startDate", "2026-10-08"); put("targetId", "stranger")
            },
            "非法周期" to args("add_stage") {
                put("cadence", "YEARLY"); put("targetMinutes", 30); put("startDate", "2026-10-08")
            },
            "非法日期格式" to args("add_stage") {
                put("cadence", "DAILY"); put("targetMinutes", 30); put("startDate", "2026/10/08")
            },
            "缺目标分钟" to args("add_stage") {
                put("cadence", "DAILY"); put("startDate", "2026-10-08"); put("targetId", "goal-target")
            },
            "非正数分钟" to args("append_one_time_stage") { put("targetMinutes", 0) },
            "未知未来规则" to args("cancel_future_rule") { put("ruleId", "stranger") },
            "规则不属于目标" to args("update_future_rule") {
                put("targetId", "other-target"); put("ruleId", "goal-rule")
                put("cadence", "DAILY"); put("targetMinutes", 30); put("effectiveFrom", "2026-10-09")
            },
            "阶段不属于目标" to args("add_future_rule") {
                put("targetId", "other-target"); put("stageId", "goal-stage")
                put("cadence", "DAILY"); put("targetMinutes", 30); put("effectiveFrom", "2026-10-09")
            },
            "未知迁移" to args("cancel_migration") { put("migrationId", "stranger") },
            "来源阶段不属于目标" to args("add_migration") {
                put("targetId", "other-target"); put("sourceStageId", "goal-stage")
                put("minutes", 10); put("destinationStartDate", "2026-10-09")
            },
        )
        cases.forEach { (label, caseArgs) ->
            val thrown = assertThrows("$label 应在解析相位失败", IllegalArgumentException::class.java) {
                tool.command(caseArgs)
            }
            assertTrue("$label 的报错应可指导纠正", thrown.message?.isNotBlank() == true)
        }
    }

    @Test
    fun an_unknown_action_is_rejected_before_any_risk_is_decided() {
        val tool = tool()
        // modify_target_minutes 属 P1.3,本票工具不得识别;删除类动作名同样不可达
        listOf("delete_stage", "delete_rule", "delete_migration", "clear_stages", "modify_target_minutes", "rename").forEach { action ->
            assertThrows("未知 action「$action」应被拒", IllegalArgumentException::class.java) {
                tool.riskLevel(buildJsonObject { put("action", action) })
            }
        }
        assertThrows("缺 action 应被拒", IllegalArgumentException::class.java) { tool.riskLevel(buildJsonObject { }) }
    }

    @Test
    fun the_action_set_is_exactly_eight_goal_actions_and_declares_no_forbidden_level() {
        // 字面量清单是独立事实源:动作全集恰好 8 个,不含任何删除/清空类 action
        assertEquals(
            setOf(
                "add_stage",
                "append_one_time_stage",
                "add_future_rule",
                "update_future_rule",
                "cancel_future_rule",
                "add_migration",
                "update_migration",
                "cancel_migration",
            ),
            SetGoalTool.ACTIONS.toSet(),
        )
        val tool = tool()
        // 永禁红线:声明不含 Forbidden;能注册进目录本身即证明构造期 Forbidden 守卫未被触发
        assertTrue(tool.riskLevels.none { it == RiskLevel.Forbidden })
        ToolCatalog(
            changeTools = listOf(tool),
            executor = FakeCommandExecutor(),
            clock = ChangeClockSource { ChangeClock(DISPATCH_NOW, DISPATCH_ZONE) },
        )
    }
}
