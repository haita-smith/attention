package com.attention.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * updateGoalStage(修改已生效时间目标,#243)的领域规则语义:
 * 修改经既有未来规则 overlay 落地,生效点为该阶段周期粒度下、从规划日推算的下一个
 * 周期边界——与「周期由开始时的规则治理」不变式一致,当前周期整段保持旧值,绝不在
 * 周期中途改写;同一阶段同一生效点的既有待生效 overlay 走更新语义不堆叠;completed
 * 阶段拒绝;一次性阶段没有下一周期边界,同样拒绝。
 *
 * 结构性不变量(不得改动快照/里程碑机制本身):已结算 PeriodSnapshot 逐字段不变、
 * 已达成里程碑不变、经验值与历史统计不变,由专项用例显式钉住。
 */
class UpdateGoalStageTest {
    /** 2026-10-07 是周三;所在周为 2026-10-05(周一)..2026-10-11(周日)。 */
    private val planningDay = LocalDate.of(2026, 10, 7)

    /** WEEKLY 阶段(目标 100,起点 2026-10-05 周一)+ 周一投入 40 分钟。 */
    private fun weeklyBase(targetMinutes: Int = 100): Triple<AttentionState, Target, GoalStage> {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()
        val state = base
            .addGoalStage(target.id, GoalCadence.WEEKLY, targetMinutes, "2026-10-05")
            .addTimeEntry("2026-10-05", 40, target.id)
        return Triple(state, target, state.goalStages.single())
    }

    @Test
    fun current_period_keeps_the_old_value_and_the_next_period_starts_the_new_one() {
        val (state, target, stage) = weeklyBase()

        val updated = state.updateGoalStage(stage.id, 50, null, planningDay)

        // overlay 规则:同阶段、阶段自己的周期类型、从下一周期边界(2026-10-12 周一)生效
        val rule = updated.futureGoalRules.single()
        assertEquals(target.id, rule.targetId)
        assertEquals(stage.id, rule.stageId)
        assertEquals(GoalCadence.WEEKLY, rule.cadence)
        assertEquals(50, rule.targetMinutes)
        assertEquals("2026-10-12", rule.effectiveFrom)

        // 当前周期整段仍由开始时的规则治理:10-07 与周期末日 10-11 都保持旧值 100
        assertEquals(100, state.effectiveStage(stage, planningDay).targetMinutes)
        assertEquals(100, updated.effectiveStage(stage, planningDay).targetMinutes)
        assertEquals(100, updated.effectiveStage(stage, LocalDate.of(2026, 10, 11)).targetMinutes)
        // 下一周期第一天起整体切换:新目标 50,生效起点即周期边界本身
        val effective = updated.effectiveStage(stage, LocalDate.of(2026, 10, 12))
        assertEquals(50, effective.targetMinutes)
        assertEquals("2026-10-12", effective.startDate)
        // 阶段本体保持原样:承诺只经 overlay 治理,历史字段不改写
        assertEquals(listOf(stage), updated.goalStages)
    }

    @Test
    fun the_boundary_follows_each_cadence_granularity_from_the_planning_day() {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()

        data class Case(val cadence: GoalCadence, val start: String, val planningDay: LocalDate, val boundary: String)

        listOf(
            Case(GoalCadence.DAILY, "2026-10-07", planningDay, "2026-10-08"),
            Case(GoalCadence.WEEKLY, "2026-10-05", planningDay, "2026-10-12"),
            // 规划日是周期最后一天:下一个边界就是明天开新周
            Case(GoalCadence.WEEKLY, "2026-10-05", LocalDate.of(2026, 10, 11), "2026-10-12"),
            Case(GoalCadence.MONTHLY, "2026-10-01", planningDay, "2026-11-01"),
            // 规划日是月末:下一个边界即次月 1 日
            Case(GoalCadence.MONTHLY, "2026-10-01", LocalDate.of(2026, 10, 31), "2026-11-01"),
        ).forEach { case ->
            val state = base.addGoalStage(target.id, case.cadence, 100, case.start)
            val stage = state.goalStages.single()

            val updated = state.updateGoalStage(stage.id, 50, null, case.planningDay)

            assertEquals(
                "$case.cadence 自 ${case.planningDay} 起的下一周期边界",
                case.boundary,
                updated.futureGoalRules.single().effectiveFrom,
            )
        }
    }

    @Test
    fun settled_snapshots_milestones_and_experience_are_structurally_untouched() {
        val (initial, _, stage) = weeklyBase(targetMinutes = 30)
        // 第一周(10-05..10-11)投入 30 分钟,达标并结算:快照与里程碑在修改之前已冻结
        val settled = initial
            .settlePeriodSnapshots(LocalDate.of(2026, 10, 12))
            .awardEligibleMilestones(LocalDate.of(2026, 10, 11))
        val snapshotsBefore = settled.periodSnapshots
        val milestonesBefore = settled.milestones
        val experienceBefore = settled.experience
        assertTrue("前置:至少一笔已结算快照", snapshotsBefore.isNotEmpty())
        assertTrue("前置:至少一枚已达成里程碑", milestonesBefore.isNotEmpty())

        val updated = settled.updateGoalStage(stage.id, 50, null, planningDay)

        // 修改只新增 overlay,不动任何已冻结的历史:快照、里程碑逐字段不变,经验值不变
        assertEquals(snapshotsBefore, updated.periodSnapshots)
        assertEquals(milestonesBefore, updated.milestones)
        assertEquals(experienceBefore, updated.experience)

        // 之后再次结算:旧周期不会按新值重算,新周期按新值 50 结算
        val resettle = updated.settlePeriodSnapshots(LocalDate.of(2026, 10, 19))
        assertEquals(snapshotsBefore, resettle.periodSnapshots.take(snapshotsBefore.size))
        val secondWeek = resettle.periodSnapshots.last()
        assertEquals(LocalDate.of(2026, 10, 12).toString(), secondWeek.periodStart)
        assertEquals(50, secondWeek.targetMinutes)
    }

    @Test
    fun a_completed_stage_rejects_modification() {
        val (state, _, stage) = weeklyBase()
        val completed = state.copy(goalStages = listOf(stage.copy(completed = true)))

        assertThrows(IllegalArgumentException::class.java) {
            completed.updateGoalStage(stage.id, 50, null, planningDay)
        }
    }

    @Test
    fun a_one_time_stage_rejects_modification() {
        val base = AttentionState().addTarget("读完一本书")
        val target = base.targets.single()
        val state = base.addGoalStage(target.id, GoalCadence.ONE_TIME, 300, "2026-10-01")
        val stage = state.goalStages.single()

        // 一次性阶段的「周期」就是阶段本身,没有下一周期边界;overlay 只能把生效起点
        // 推到阶段中途,会重写当前周期的进度窗口,因此结构性拒绝
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            state.updateGoalStage(stage.id, 200, null, planningDay)
        }
        assertTrue(thrown.message?.contains("一次性") == true)
    }

    @Test
    fun repeated_modification_on_the_same_boundary_updates_in_place() {
        val (state, _, stage) = weeklyBase()
        val first = state.updateGoalStage(stage.id, 45, null, planningDay)
        val firstRule = first.futureGoalRules.single()

        val second = first.updateGoalStage(stage.id, 60, "2026-10-18", planningDay)

        // 同阶段同生效点走更新语义:不堆叠,原地替换同一笔规则
        assertEquals(1, second.futureGoalRules.size)
        val rule = second.futureGoalRules.single()
        assertEquals(firstRule.id, rule.id)
        assertEquals(60, rule.targetMinutes)
        assertEquals("2026-10-18", rule.dueDate)
        assertEquals("2026-10-12", rule.effectiveFrom)
        // 两次修改都不触碰当前周期
        assertEquals(100, second.effectiveStage(stage, planningDay).targetMinutes)
    }

    @Test
    fun an_existing_pending_overlay_at_the_same_boundary_is_updated_not_stacked() {
        val (state, target, stage) = weeklyBase()
        val pending = FutureGoalRule(
            targetId = target.id,
            stageId = stage.id,
            cadence = GoalCadence.WEEKLY,
            targetMinutes = 45,
            effectiveFrom = "2026-10-12",
        )
        val withPending = state.addFutureGoalRule(pending, planningDay)

        val updated = withPending.updateGoalStage(stage.id, 60, null, planningDay)

        assertEquals(1, updated.futureGoalRules.size)
        assertEquals(pending.id, updated.futureGoalRules.single().id)
        assertEquals(60, updated.futureGoalRules.single().targetMinutes)
    }

    @Test
    fun a_pending_overlay_at_a_different_boundary_is_preserved() {
        val (state, target, stage) = weeklyBase()
        val pending = FutureGoalRule(
            targetId = target.id,
            stageId = stage.id,
            cadence = GoalCadence.WEEKLY,
            targetMinutes = 45,
            effectiveFrom = "2026-10-19",
        )
        val withPending = state.addFutureGoalRule(pending, planningDay)

        val updated = withPending.updateGoalStage(stage.id, 50, null, planningDay)

        // 不同生效点的 overlay 各自独立:新增与本笔修改共存,既有的不被触碰
        assertEquals(2, updated.futureGoalRules.size)
        val modified = updated.futureGoalRules.single { it.effectiveFrom == "2026-10-12" }
        assertEquals(50, modified.targetMinutes)
        assertEquals(pending, updated.futureGoalRules.single { it.effectiveFrom == "2026-10-19" })
    }

    @Test
    fun engine_validation_failures_are_forwarded() {
        val (state, target, stage) = weeklyBase()

        // 未知阶段
        assertThrows(IllegalStateException::class.java) {
            state.updateGoalStage("stranger", 50, null, planningDay)
        }
        // 非正数分钟
        assertThrows(IllegalArgumentException::class.java) {
            state.updateGoalStage(stage.id, 0, null, planningDay)
        }
        // 截止早于生效点(生效点为 2026-10-12)
        assertThrows(IllegalArgumentException::class.java) {
            state.updateGoalStage(stage.id, 50, "2026-10-11", planningDay)
        }
        // 归档目标不能产生新的承诺
        val archived = state.copy(targets = listOf(target.copy(archived = true)))
        assertThrows(IllegalArgumentException::class.java) {
            archived.updateGoalStage(stage.id, 50, null, planningDay)
        }
    }
}
