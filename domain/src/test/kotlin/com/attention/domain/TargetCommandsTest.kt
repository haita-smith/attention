package com.attention.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标树管理 command(P1.1 #241)的映射与语义:成功路径断言薄包装转发了既有引擎
 * 纯函数的结果,失败路径断言领域校验原样上行;未来生效/防环等领域语义的完整覆盖
 * 在 [AttentionEngineTest],这里只钉 command 这一层的转发契约。
 *
 * 全部目标动作不产时间记录与里程碑,每个成功用例都顺带断言结果只带 state。
 */
class TargetCommandsTest {
    private val date = LocalDate.of(2026, 10, 8)
    private val utc = ZoneOffset.UTC
    // UTC 正午之后,远离规划日界线(03:00),规划日即当日
    private val now = Instant.parse("2026-10-08T12:34:56Z")

    private fun assertNoTimeFacts(result: AttentionCommandResult) {
        assertTrue(result.timeEntries.isEmpty())
        assertTrue(result.milestones.isEmpty())
        assertTrue(result.removedTimeEntries.isEmpty())
    }

    @Test
    fun create_target_command_adds_a_root_target_with_no_time_facts() {
        val result = AttentionCommand.CreateTarget("阅读").execute(AttentionState(), now, utc)
        val target = result.state.targets.single()
        assertEquals("阅读", target.title)
        assertNull(target.parentId)
        assertFalse(target.archived)
        assertNoTimeFacts(result)
    }

    @Test
    fun create_target_command_places_the_new_target_under_an_existing_parent() {
        val base = AttentionState().addTarget("工作")
        val parent = base.targets.single()
        val result = AttentionCommand.CreateTarget("深工作", parent.id).execute(base, now, utc)
        assertEquals(parent.id, result.state.targets.last().parentId)
        assertNoTimeFacts(result)
    }

    @Test
    fun create_target_command_forwards_domain_validation_failures() {
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.CreateTarget("   ").execute(AttentionState(), now, utc)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.CreateTarget("孤儿", "missing-parent").execute(AttentionState(), now, utc)
        }
    }

    @Test
    fun rename_target_command_renames_an_existing_target_and_forwards_failures() {
        val base = AttentionState().addTarget("旧名")
        val target = base.targets.single()
        val result = AttentionCommand.RenameTarget(target.id, "新名").execute(base, now, utc)
        assertEquals("新名", result.state.targets.single().title)
        assertNoTimeFacts(result)
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.RenameTarget("missing", "任意").execute(base, now, utc)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.RenameTarget(target.id, " ").execute(base, now, utc)
        }
    }

    @Test
    fun reorder_target_command_moves_a_target_within_its_siblings_and_forwards_failures() {
        val state = AttentionState().addTarget("甲").addTarget("乙").addTarget("丙")
        val last = state.targets.last()
        val result = AttentionCommand.ReorderTarget(last.id, 0).execute(state, now, utc)
        assertEquals(listOf("丙", "甲", "乙"), result.state.targets.sortedBy { it.sortOrder }.map { it.title })
        assertNoTimeFacts(result)
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.ReorderTarget(last.id, 3).execute(state, now, utc)
        }
    }

    @Test
    fun set_target_expanded_command_toggles_branch_visibility_without_time_facts() {
        val base = AttentionState().addTarget("树")
        val target = base.targets.single()
        val collapsed = AttentionCommand.SetTargetExpanded(target.id, false).execute(base, now, utc)
        assertFalse(collapsed.state.targets.single().expanded)
        val reopened = AttentionCommand.SetTargetExpanded(target.id, true).execute(collapsed.state, now, utc)
        assertTrue(reopened.state.targets.single().expanded)
        assertNoTimeFacts(collapsed)
        assertNoTimeFacts(reopened)
    }

    @Test
    fun move_target_command_reparents_immediately_and_appends_to_the_new_parent() {
        val roots = AttentionState().addTarget("旧父").addTarget("新父")
        val oldParent = roots.targets[0]
        val newParent = roots.targets[1]
        val withChild = roots.addTarget("孩子", oldParent.id)
        val child = withChild.targets.last()

        val result = AttentionCommand.MoveTarget(child.id, newParent.id).execute(withChild, now, utc)
        val moved = result.state.targets.first { it.id == child.id }
        assertEquals(newParent.id, moved.parentId)
        assertEquals(0, moved.sortOrder)
        assertNoTimeFacts(result)

        // 防移入自身后代:在换父前,把父目标挪到它自己的孩子之下必须被领域拒绝
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.MoveTarget(oldParent.id, child.id).execute(withChild, now, utc)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.MoveTarget("missing", newParent.id).execute(withChild, now, utc)
        }
    }

    @Test
    fun archive_target_command_archives_and_detaches_a_running_timer() {
        val base = AttentionState().addTarget("计时目标")
        val target = base.targets.single()
        val timing = base.startTimer(target.id, now.minusSeconds(60), utc)
        val result = AttentionCommand.ArchiveTarget(target.id).execute(timing, now, utc)
        assertTrue(result.state.targets.single().archived)
        assertNull(result.state.activeTimer?.targetId)
        assertNoTimeFacts(result)
    }

    @Test
    fun restore_targets_command_restores_cascading_ancestors_and_forwards_unknown_ids() {
        val base = AttentionState().addTarget("父")
        val parent = base.targets.single()
        val withChild = base.addTarget("子", parent.id)
        val child = withChild.targets.last()
        val archived = withChild.archiveTarget(parent.id).archiveTarget(child.id)

        val result = AttentionCommand.RestoreTargets(setOf(child.id)).execute(archived, now, utc)
        assertTrue(result.state.targets.none { it.archived })
        assertNoTimeFacts(result)

        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.RestoreTargets(setOf("missing")).execute(archived, now, utc)
        }
    }

    @Test
    fun schedule_target_move_command_plans_a_future_move_from_the_planning_day() {
        val base = AttentionState().addTarget("目标").addTarget("未来父")
        val target = base.targets[0]
        val futureParent = base.targets[1]

        val result = AttentionCommand.ScheduleTargetMove(target.id, futureParent.id, "2026-10-12").execute(base, now, utc)
        val move = result.state.targetMoves.single()
        assertEquals(target.id, move.targetId)
        assertEquals(futureParent.id, move.parentId)
        assertEquals("2026-10-12", move.effectiveFrom)
        assertNoTimeFacts(result)

        val toRoot = AttentionCommand.ScheduleTargetMove(target.id, null, "2026-10-15").execute(base, now, utc)
        assertNull(toRoot.state.targetMoves.last().parentId)

        // 生效日不晚于当前规划日(执行时刻的规划日是 2026-10-08)必须被领域拒绝
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.ScheduleTargetMove(target.id, null, date.toString()).execute(base, now, utc)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.ScheduleTargetMove("missing", null, "2026-10-12").execute(base, now, utc)
        }
    }

    @Test
    fun update_target_move_command_edits_a_pending_move_in_place_keeping_identity() {
        val base = AttentionState().addTarget("目标").addTarget("父甲").addTarget("父乙")
        val target = base.targets[0]
        val parentA = base.targets[1]
        val parentB = base.targets[2]
        val scheduled = base.addFutureTargetMove(target.id, parentA.id, "2026-10-20", date)
        val move = scheduled.targetMoves.single()

        val result = AttentionCommand.UpdateTargetMove(move.id, parentB.id, "2026-10-25").execute(scheduled, now, utc)
        val updated = result.state.targetMoves.single()
        assertEquals(move.id, updated.id)
        assertEquals(target.id, updated.targetId)
        assertEquals(parentB.id, updated.parentId)
        assertEquals("2026-10-25", updated.effectiveFrom)
        assertNoTimeFacts(result)

        assertThrows(IllegalStateException::class.java) {
            AttentionCommand.UpdateTargetMove("missing", parentB.id, "2026-10-25").execute(scheduled, now, utc)
        }
        // 已生效的移动不可改:now 推进到生效日当天,领域按「只能修改尚未生效」拒绝
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.UpdateTargetMove(move.id, parentB.id, "2026-11-01")
                .execute(scheduled, Instant.parse("2026-10-20T12:00:00Z"), utc)
        }
    }

    @Test
    fun cancel_target_move_command_removes_a_pending_move_and_forwards_failures() {
        val base = AttentionState().addTarget("目标").addTarget("未来父")
        val target = base.targets[0]
        val futureParent = base.targets[1]
        val scheduled = base.addFutureTargetMove(target.id, futureParent.id, "2026-10-20", date)
        val move = scheduled.targetMoves.single()

        val result = AttentionCommand.CancelTargetMove(move.id).execute(scheduled, now, utc)
        assertTrue(result.state.targetMoves.isEmpty())
        assertNoTimeFacts(result)

        assertThrows(IllegalStateException::class.java) {
            AttentionCommand.CancelTargetMove("missing").execute(scheduled, now, utc)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.CancelTargetMove(move.id).execute(scheduled, Instant.parse("2026-10-20T12:00:00Z"), utc)
        }
    }
}
