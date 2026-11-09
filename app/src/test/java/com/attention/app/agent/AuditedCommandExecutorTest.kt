package com.attention.app.agent

import com.attention.app.data.audit.AiChangeAudit
import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionState
import com.attention.domain.Target
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 包装执行通道的契约(ADR-0009 写入点):
 * - 每条变更 command 派发**前**调 beforeChange,快照内容是派发前那一刻的状态;
 * - 快照写失败即本次变更失败——异常上行、派发中止,数据不动;
 * - 运行边界内逐次收集领域 command 简单类名(不去重,去重归审计门面),
 *   边界外(手动路径、确认门在途)的派发不入名单。
 */
class AuditedCommandExecutorTest {

    /** 派发前后的两份可区分状态:标题不同,等值断言能分辨捕获发生在派发前还是派发后。 */
    private val preDispatchState = AttentionState(targets = listOf(Target(id = "t1", title = "派发前")))
    private val postDispatchState = AttentionState(targets = listOf(Target(id = "t1", title = "派发后")))

    private fun executor(
        current: () -> AttentionState,
        delegate: FakeDispatchExecutor = FakeDispatchExecutor(),
        snapshots: RecordingSnapshotStore = RecordingSnapshotStore(),
    ): AuditedCommandExecutor =
        AuditedCommandExecutor(delegate, AiChangeAudit(RecordingTraceStore(), snapshots), current)

    @Test
    fun a_change_command_snapshots_the_pre_dispatch_state_before_dispatching() = runBlocking {
        var current = preDispatchState
        val snapshots = RecordingSnapshotStore()
        // 派发时把「当前状态」推进到派发后:若实现先派发后捕获,快照里就是派发后的状态
        val delegate = FakeDispatchExecutor(onDispatch = { current = postDispatchState })
        val audited = executor(current = { current }, delegate = delegate, snapshots = snapshots)

        audited.beginRun()
        audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)

        assertEquals(listOf(preDispatchState), snapshots.captured)
        assertEquals(listOf(DISPATCH_NOW), delegate.nows)
        assertEquals(listOf(DISPATCH_ZONE), delegate.zones)
    }

    @Test
    fun a_second_change_in_the_same_boundary_snapshots_the_state_left_by_the_first() = runBlocking {
        var current = preDispatchState
        val snapshots = RecordingSnapshotStore()
        val delegate = FakeDispatchExecutor(onDispatch = { current = postDispatchState })
        val audited = executor(current = { current }, delegate = delegate, snapshots = snapshots)

        audited.beginRun()
        audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)
        audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)

        assertEquals(listOf(preDispatchState, postDispatchState), snapshots.captured)
    }

    @Test
    fun every_dispatched_command_is_collected_in_order_without_deduplication() = runBlocking {
        val audited = executor(current = { AttentionState() })

        audited.beginRun()
        audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)
        audited.execute(AttentionCommand.DeleteTimeEntry("e1"), DISPATCH_NOW, DISPATCH_ZONE)
        audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)

        assertEquals(listOf("StopTimer", "DeleteTimeEntry", "StopTimer"), audited.finishRun())
    }

    @Test
    fun dispatches_outside_a_run_boundary_are_not_collected() = runBlocking {
        val audited = executor(current = { AttentionState() })

        audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)

        assertEquals(emptyList<String>(), audited.finishRun())
    }

    @Test
    fun a_failed_snapshot_aborts_the_dispatch_and_the_change_does_not_land() = runBlocking {
        var current = preDispatchState
        val snapshots = RecordingSnapshotStore(failure = IllegalStateException("快照写失败"))
        val delegate = FakeDispatchExecutor()
        val audited = executor(current = { current }, delegate = delegate, snapshots = snapshots)

        audited.beginRun()
        val thrown = runCatching {
            audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)
        }.exceptionOrNull()

        assertEquals("快照写失败", thrown?.message)
        assertEquals(0, delegate.dispatchCount)
        assertEquals(emptyList<String>(), audited.finishRun())
    }

    @Test
    fun a_failed_dispatch_is_not_recorded_as_a_submitted_command() = runBlocking {
        val delegate = FakeDispatchExecutor(failure = IllegalStateException("通道故障"))
        val audited = executor(current = { AttentionState() }, delegate = delegate)

        audited.beginRun()
        val thrown = runCatching {
            audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)
        }.exceptionOrNull()

        assertEquals("通道故障", thrown?.message)
        assertEquals(emptyList<String>(), audited.finishRun())
    }

    @Test
    fun finishing_the_boundary_closes_it_and_a_second_finish_yields_nothing() = runBlocking {
        val audited = executor(current = { AttentionState() })

        audited.beginRun()
        audited.execute(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)
        assertEquals(listOf("StopTimer"), audited.finishRun())
        assertEquals(emptyList<String>(), audited.finishRun())
    }
}
