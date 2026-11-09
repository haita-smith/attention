package com.attention.domain

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计时 command(P2.1 #245)的段语义转发:StartTimer/PauseTimer/ResumeTimer 是既有
 * 计时纯函数的薄包装,规则只活在 AttentionEngine 一处,因此每个用例同时断言
 * 「与纯函数执行结果完全一致」(转发零漂移)与「段语义可见的外部事实」。
 * 停止/切换复用既有 StopTimer/SwitchTimer,其语义(跨规划日拆分、切换保存前段、
 * 无活动计时器 no-op)由 AttentionCommandTest 与 AttentionEngineTest 既有覆盖,不重复。
 */
class TimerCommandsTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-08T00:30:00Z")

    private fun stateWithTargets(): AttentionState =
        AttentionState().addTarget("专注").addTarget("写作")

    @Test
    fun start_timer_command_starts_a_targeted_or_unowned_timer_when_idle() {
        val base = stateWithTargets()
        val focus = base.targets[0]

        val targeted = AttentionCommand.StartTimer(focus.id).execute(base, now, utc)
        assertEquals(base.startTimer(focus.id, now, utc), targeted.state)
        assertEquals(focus.id, targeted.state.activeTimer?.targetId)
        assertEquals(now.toEpochMilli(), targeted.state.activeTimer?.startedAtEpochMillis)
        assertNull(targeted.state.activeTimer?.scheduleEntryId)
        assertTrue(targeted.timeEntries.isEmpty() && targeted.milestones.isEmpty())

        val unowned = AttentionCommand.StartTimer(null).execute(base, now, utc)
        assertEquals(base.startTimer(null, now, utc), unowned.state)
        assertNull(unowned.state.activeTimer?.targetId)
    }

    @Test
    fun start_timer_command_requires_no_active_timer() {
        val base = stateWithTargets()
        val focus = base.targets[0]
        val writing = base.targets[1]
        val running = base.startTimer(focus.id, now, utc)

        // 「开始要求无活动计时器」由引擎把守,command 原样上行,不复制也不吞并
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            AttentionCommand.StartTimer(writing.id).execute(running, now, utc)
        }
        assertEquals("已有活动计时器，请先暂停或结束", thrown.message)
        assertNull(AttentionCommand.StartTimer(writing.id).execute(base, now, utc).state.activeTimer?.scheduleEntryId)
    }

    @Test
    fun pause_timer_command_folds_the_running_segment_into_accumulated_time() {
        val base = stateWithTargets()
        val focus = base.targets[0]
        val started = base.startTimer(focus.id, Instant.parse("2026-10-08T00:00:00Z"), utc)
        val pauseAt = Instant.parse("2026-10-08T00:05:00Z")

        val result = AttentionCommand.PauseTimer.execute(started, pauseAt, utc)

        // 转发零漂移 + 段语义外部事实:当前段折入累计,paused 置位,段收口在暂停时刻
        assertEquals(started.pauseTimer(pauseAt), result.state)
        val timer = result.state.activeTimer ?: throw AssertionError("暂停后计时器不应消失")
        assertTrue(timer.paused)
        assertEquals(5 * 60_000L, timer.accumulatedMillis)
        assertEquals(1, timer.completedSegments.size)
        assertEquals(5 * 60_000L, timer.elapsedMillis(pauseAt))
        // 无活动计时器与已暂停时保持原状:暂停是幂等 no-op,不产生第二段
        assertEquals(base, AttentionCommand.PauseTimer.execute(base, pauseAt, utc).state)
        assertEquals(result.state, AttentionCommand.PauseTimer.execute(result.state, pauseAt, utc).state)
    }

    @Test
    fun resume_timer_command_opens_a_new_segment_and_keeps_settled_time() {
        val base = stateWithTargets()
        val focus = base.targets[0]
        val paused = base
            .startTimer(focus.id, Instant.parse("2026-10-08T00:00:00Z"), utc)
            .pauseTimer(Instant.parse("2026-10-08T00:05:00Z"))
        val resumeAt = Instant.parse("2026-10-08T00:10:00Z")

        val result = AttentionCommand.ResumeTimer.execute(paused, resumeAt, utc)

        // 转发零漂移 + 段语义外部事实:重新开段但已结算的累计不变,未暂停时与空闲时原状
        assertEquals(paused.resumeTimer(resumeAt), result.state)
        val timer = result.state.activeTimer ?: throw AssertionError("恢复后计时器不应消失")
        assertTrue(!timer.paused)
        assertEquals(5 * 60_000L, timer.accumulatedMillis)
        assertEquals(resumeAt.toEpochMilli(), timer.startedAtEpochMillis)
        // 幂等 no-op:对已恢复的运行态计时器重复恢复、对无活动计时器的空闲态恢复,都保持原状
        assertEquals(result.state, AttentionCommand.ResumeTimer.execute(result.state, resumeAt, utc).state)
        assertEquals(base, AttentionCommand.ResumeTimer.execute(base, resumeAt, utc).state)
    }

    @Test
    fun pause_and_resume_commands_forward_segment_semantics_to_the_settlement() {
        val base = stateWithTargets()
        val focus = base.targets[0]
        val running = base
            .let { AttentionCommand.StartTimer(focus.id).execute(it, Instant.parse("2026-10-08T00:00:00Z"), utc) }
            .state
            .let { AttentionCommand.PauseTimer.execute(it, Instant.parse("2026-10-08T00:05:00Z"), utc) }
            .state
            .let { AttentionCommand.ResumeTimer.execute(it, Instant.parse("2026-10-08T00:10:00Z"), utc) }
            .state

        // 恢复续段:暂停区间(00:05–00:10)不入账,前后两个运行段(各 5 分钟)落同一
        // 规划日合桶,结算为一条 10 分钟记录
        val stopped = AttentionCommand.StopTimer.execute(running, Instant.parse("2026-10-08T00:15:00Z"), utc)
        assertEquals(1, stopped.timeEntries.size)
        assertEquals(10, stopped.timeEntries.single().durationMinutes)
        assertEquals(focus.id, stopped.timeEntries.single().targetId)
        assertEquals(TimeEntrySource.TIMER, stopped.timeEntries.single().source)
        assertNull(stopped.state.activeTimer)
    }
}
