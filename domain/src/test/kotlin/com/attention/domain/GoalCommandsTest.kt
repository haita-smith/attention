package com.attention.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * set_goal 工具族(#242)八条目标设定 command 的映射与语义:
 * 全部是对既有引擎纯函数的薄包装,这里只钉「command 把 (state, now, zone) 转发给了哪条
 * 引擎函数、返回值只带 state」,引擎自身的规则语义(notBefore、迁移可用分钟、生效链)
 * 由 AttentionEngineTest 既有覆盖,不在此重复。
 */
class GoalCommandsTest {
    private val date = LocalDate.of(2026, 10, 8)
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-08T12:00:00Z")

    @Test
    fun add_goal_stage_command_appends_the_stage_and_returns_state_only() {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()

        val result = AttentionCommand.AddGoalStage(target.id, GoalCadence.DAILY, 30, date.toString())
            .execute(base, now, utc)

        val stage = result.state.goalStages.single()
        assertEquals(target.id, stage.targetId)
        assertEquals(GoalCadence.DAILY, stage.cadence)
        assertEquals(30, stage.targetMinutes)
        assertEquals(date.toString(), stage.startDate)
        assertEquals(null, stage.dueDate)
        // 目标动作不产时间记录与里程碑:返回值只带 state
        assertTrue(result.timeEntries.isEmpty())
        assertTrue(result.milestones.isEmpty())
        assertTrue(result.removedTimeEntries.isEmpty())
    }

    @Test
    fun add_goal_stage_command_forwards_engine_validation_failures() {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()

        // 未知目标
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddGoalStage("stranger", GoalCadence.DAILY, 30, date.toString()).execute(base, now, utc)
        }
        // 截止早于开始
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddGoalStage(target.id, GoalCadence.DAILY, 30, date.toString(), date.minusDays(1).toString())
                .execute(base, now, utc)
        }
        // 非正数分钟
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddGoalStage(target.id, GoalCadence.DAILY, 0, date.toString()).execute(base, now, utc)
        }
        // 已有一次性阶段时再直接新增一次性阶段被引擎拒绝
        val withStage = base.addGoalStage(target.id, GoalCadence.ONE_TIME, 30, date.toString())
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddGoalStage(target.id, GoalCadence.ONE_TIME, 20, date.plusDays(1).toString())
                .execute(withStage, now, utc)
        }
    }

    @Test
    fun append_one_time_goal_stage_command_starts_the_new_stage_at_the_planning_day() {
        val base = AttentionState().addTarget("长跑")
        val target = base.targets.single()
        val previousStart = date.minusDays(7)
        val state = base
            .addGoalStage(target.id, GoalCadence.ONE_TIME, 30, previousStart.toString())
            .addTimeEntry(previousStart.toString(), 30, target.id)

        val result = AttentionCommand.AppendOneTimeGoalStage(target.id, 20).execute(state, now, utc)

        val stages = result.state.goalStagesForTarget(target.id)
        assertEquals(2, stages.size)
        val appended = stages.last()
        assertEquals(GoalCadence.ONE_TIME, appended.cadence)
        assertEquals(20, appended.targetMinutes)
        // 开始日取 command 折算出的当前规划日(now=正午 UTC,规划日即 2026-10-08)
        assertEquals(date.toString(), appended.startDate)
        assertTrue(result.timeEntries.isEmpty())
        assertTrue(result.milestones.isEmpty())
    }

    @Test
    fun append_one_time_goal_stage_command_forwards_engine_validation_failures() {
        val base = AttentionState().addTarget("长跑")
        val target = base.targets.single()

        // 没有任何阶段可追加
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AppendOneTimeGoalStage(target.id, 20).execute(base, now, utc)
        }
        // 上一阶段是周期阶段而非一次性阶段
        val dailyOnly = base.addGoalStage(target.id, GoalCadence.DAILY, 30, date.minusDays(7).toString())
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AppendOneTimeGoalStage(target.id, 20).execute(dailyOnly, now, utc)
        }
        // 上一阶段尚未达成
        val unmet = base.addGoalStage(target.id, GoalCadence.ONE_TIME, 30, date.minusDays(7).toString())
            .addTimeEntry(date.minusDays(7).toString(), 10, target.id)
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AppendOneTimeGoalStage(target.id, 20).execute(unmet, now, utc)
        }
        // 归档目标
        val archived = AttentionState(targets = listOf(target.copy(archived = true)))
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AppendOneTimeGoalStage(target.id, 20).execute(archived, now, utc)
        }
    }

    @Test
    fun add_future_goal_rule_command_forwards_the_planning_day_as_not_before() {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()
        val rule = FutureGoalRule(targetId = target.id, cadence = GoalCadence.WEEKLY, targetMinutes = 45, effectiveFrom = date.plusDays(1).toString())

        val result = AttentionCommand.AddFutureGoalRule(rule).execute(base, now, utc)

        assertEquals(listOf(rule), result.state.futureGoalRules)
        assertTrue(result.timeEntries.isEmpty())
        assertTrue(result.milestones.isEmpty())

        // 生效日等于当前规划日被拒绝:钉住 command 折算的 notBefore 就是规划日本身,
        // 若转发成更早的日子(如纪元起点),这条会被引擎放行
        val effectiveToday = rule.copy(effectiveFrom = date.toString())
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddFutureGoalRule(effectiveToday).execute(base, now, utc)
        }
        // 未知目标
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddFutureGoalRule(rule.copy(targetId = "stranger")).execute(base, now, utc)
        }
    }

    @Test
    fun update_future_goal_rule_command_updates_a_pending_rule_in_place() {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()
        val existing = FutureGoalRule(targetId = target.id, cadence = GoalCadence.WEEKLY, targetMinutes = 45, effectiveFrom = date.plusDays(1).toString())
        val state = base.addFutureGoalRule(existing, date)

        val updated = existing.copy(targetMinutes = 60, dueDate = date.plusDays(8).toString())
        val result = AttentionCommand.UpdateFutureGoalRule(updated).execute(state, now, utc)

        assertEquals(listOf(updated), result.state.futureGoalRules)

        // 未知规则 id:引擎以 error 上行
        assertThrows(IllegalStateException::class.java) {
            AttentionCommand.UpdateFutureGoalRule(updated.copy(id = "stranger")).execute(state, now, utc)
        }
        // 目标与阶段不可改
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.UpdateFutureGoalRule(updated.copy(targetId = "other")).execute(state, now, utc)
        }
        // 已经生效(生效日不晚于规划日)的规则不能修改
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.UpdateFutureGoalRule(updated.copy(effectiveFrom = date.toString())).execute(state, now, utc)
        }
    }

    @Test
    fun cancel_future_goal_rule_command_removes_only_the_pending_rule() {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()
        val first = FutureGoalRule(targetId = target.id, cadence = GoalCadence.WEEKLY, targetMinutes = 45, effectiveFrom = date.plusDays(1).toString())
        val second = first.copy(id = "second-rule", effectiveFrom = date.plusDays(2).toString())
        val state = base.addFutureGoalRule(first, date).addFutureGoalRule(second, date)

        val result = AttentionCommand.CancelFutureGoalRule(first.id).execute(state, now, utc)

        assertEquals(listOf(second), result.state.futureGoalRules)

        // 已经生效的规则不能取消(规则在更早的规划日登记,如今已跨过生效日)
        val effective = first.copy(effectiveFrom = date.toString())
        val effectiveState = base.addFutureGoalRule(effective, date.minusDays(1))
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.CancelFutureGoalRule(effective.id).execute(effectiveState, now, utc)
        }
        // 未知规则 id
        assertThrows(IllegalStateException::class.java) {
            AttentionCommand.CancelFutureGoalRule("stranger").execute(state, now, utc)
        }
    }

    /** 迁移用例共用底座:一个缺口 20 分钟(目标 30、已投 10)的一次性阶段。 */
    private fun migrationBase(): Triple<AttentionState, Target, GoalStage> {
        val base = AttentionState().addTarget("阅读")
        val target = base.targets.single()
        val stageStart = date.minusDays(2)
        val state = base
            .addGoalStage(target.id, GoalCadence.ONE_TIME, 30, stageStart.toString())
            .addTimeEntry(stageStart.toString(), 10, target.id)
        val stage = state.goalStages.single()
        return Triple(state, target, stage)
    }

    private fun migration(sourceStageId: String, targetId: String, minutes: Int = 10, destinationStart: LocalDate = date.plusDays(1)) =
        Migration(targetId = targetId, sourceStageId = sourceStageId, minutes = minutes, destinationStartDate = destinationStart.toString())

    @Test
    fun add_migration_command_reserves_gap_minutes_from_the_planning_day() {
        val (state, target, stage) = migrationBase()
        val command = migration(stage.id, target.id)

        val result = AttentionCommand.AddMigration(command).execute(state, now, utc)

        assertEquals(listOf(command), result.state.migrations)
        assertTrue(result.timeEntries.isEmpty())
        assertTrue(result.milestones.isEmpty())

        // 去向开始日等于规划日被拒绝:progressRange 的终点是 command 折算的规划日,
        // 这条抛错钉住 onDate 的转发(若转发成更早的日子,规划日开始的迁移会被放行)
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddMigration(migration(stage.id, target.id, destinationStart = date)).execute(state, now, utc)
        }
        // 迁移分钟不得超过来源阶段的剩余缺口(缺口 20)
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.AddMigration(migration(stage.id, target.id, minutes = 25)).execute(state, now, utc)
        }
        // 来源阶段不存在
        assertThrows(IllegalStateException::class.java) {
            AttentionCommand.AddMigration(migration("stranger", target.id)).execute(state, now, utc)
        }
    }

    @Test
    fun update_migration_command_replaces_the_fields_of_an_active_migration() {
        val (state, target, stage) = migrationBase()
        val existing = migration(stage.id, target.id)
        val withMigration = state.addMigration(existing, date)

        val updated = existing.copy(minutes = 15, destinationStartDate = date.plusDays(2).toString())
        val result = AttentionCommand.UpdateMigration(updated).execute(withMigration, now, utc)

        // 更新是原地替换:不与原迁移叠加成两条
        assertEquals(listOf(updated), result.state.migrations)

        // 未知迁移 id
        assertThrows(IllegalStateException::class.java) {
            AttentionCommand.UpdateMigration(updated.copy(id = "stranger")).execute(withMigration, now, utc)
        }
        // 已取消的迁移不能更新
        val cancelled = existing.copy(cancelled = true)
        val cancelledState = withMigration.copy(migrations = listOf(cancelled))
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.UpdateMigration(cancelled.copy(minutes = 15)).execute(cancelledState, now, utc)
        }
    }

    @Test
    fun cancel_migration_command_marks_cancelled_without_deleting_the_record() {
        val (state, target, stage) = migrationBase()
        val existing = migration(stage.id, target.id)
        val withMigration = state.addMigration(existing, date)

        val result = AttentionCommand.CancelMigration(existing.id).execute(withMigration, now, utc)

        // 取消是标记而非删除:迁移记录保留在案,历史可追溯
        val cancelled = result.state.migrations.single()
        assertEquals(existing.id, cancelled.id)
        assertTrue(cancelled.cancelled)

        // 未知迁移 id
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.CancelMigration("stranger").execute(withMigration, now, utc)
        }
    }
}
