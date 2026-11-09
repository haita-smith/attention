package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import java.time.Instant
import java.time.ZoneId

/**
 * 真执行领域 command 的执行通道(流程级测试共用):记录派发实况,并按生产契约
 * 返回 command 在底座状态上执行后的真实结果——观测投影读到的是执行后的状态。
 */
internal class ExecutingExecutor(private val state: AttentionState) : CommandExecutor {
    val commands = mutableListOf<AttentionCommand>()
    val nows = mutableListOf<Instant>()
    val zones = mutableListOf<ZoneId>()

    val dispatchCount: Int get() = commands.size

    override suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult {
        commands += command
        nows += now
        zones += zone
        return command.execute(state, now, zone)
    }
}
