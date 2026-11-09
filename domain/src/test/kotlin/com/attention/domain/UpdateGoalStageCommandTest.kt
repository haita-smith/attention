package com.attention.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateGoalStage command(#243)的映射:把 (state, now, zone) 转发给
 * [AttentionState.updateGoalStage],notBefore 取 command 折算出的当前规划日,
 * 返回值只带 state。领域规则语义(边界推算、去重更新、不变量)由 UpdateGoalStageTest
 * 覆盖,不在此重复。
 */
class UpdateGoalStageCommandTest {
    /** 2026-03-06 是周五;所在周为 2026-03-02(周一)..2026-03-08(周日)。 */
    private val date = LocalDate.of(2026, 3, 6)
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-03-06T12:00:00Z")

    /** WEEKLY 阶段(目标 100,起点周一 2026-03-02)。 */
    private fun weeklyState(): Pair<AttentionState, GoalStage> {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()
        val state = base.addGoalStage(target.id, GoalCadence.WEEKLY, 100, "2026-03-02")
        return state to state.goalStages.single()
    }

    @Test
    fun update_goal_stage_command_lands_the_overlay_from_the_planning_day() {
        val (state, stage) = weeklyState()

        val result = AttentionCommand.UpdateGoalStage(stage.id, 50).execute(state, now, utc)

        // notBefore 是 command 折算的规划日(2026-03-06):下一周期边界 2026-03-09(周一);
        // 若转发成更早的日子,边界会落在别的周期上,这条断言即失效
        val rule = result.state.futureGoalRules.single()
        assertEquals(stage.id, rule.stageId)
        assertEquals(GoalCadence.WEEKLY, rule.cadence)
        assertEquals(50, rule.targetMinutes)
        assertEquals("2026-03-09", rule.effectiveFrom)
        // 目标动作不产时间记录与里程碑:返回值只带 state
        assertTrue(result.timeEntries.isEmpty())
        assertTrue(result.milestones.isEmpty())
        assertTrue(result.removedTimeEntries.isEmpty())
    }

    @Test
    fun update_goal_stage_command_carries_the_optional_due_date_and_forwards_failures() {
        val (state, stage) = weeklyState()

        val result = AttentionCommand.UpdateGoalStage(stage.id, 50, "2026-03-15").execute(state, now, utc)
        assertEquals("2026-03-15", result.state.futureGoalRules.single().dueDate)

        // 已完成阶段被引擎拒绝
        val completed = state.copy(goalStages = listOf(stage.copy(completed = true)))
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.UpdateGoalStage(stage.id, 50).execute(completed, now, utc)
        }
        // 未知阶段
        assertThrows(IllegalStateException::class.java) {
            AttentionCommand.UpdateGoalStage("stranger", 50).execute(state, now, utc)
        }
        // 截止早于生效点(2026-03-09)
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.UpdateGoalStage(stage.id, 50, "2026-03-08").execute(state, now, utc)
        }
    }
}
