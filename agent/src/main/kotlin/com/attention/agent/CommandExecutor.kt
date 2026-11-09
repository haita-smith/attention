package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import java.time.Instant
import java.time.ZoneId

/**
 * 领域 command 的执行接缝。签名对齐组合根唯一的派发口
 * （`AttentionStateRepository.executeCommand(command, now, zone)`），由组合根注入：
 * `:agent` 对 Room、DataStore 与 app 层零感知，将来的接线是一行委托而不是一次改造。
 * 只吸收 command 通道，不吸收 `updateBusiness(transform)` 遗留旁路——
 * 变更工具的一切修改都走同一条可补偿的领域通道（#226）。
 */
fun interface CommandExecutor {
    suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult
}

/** 一次变更派发的时钟事实：now 与 zone 成对取用（ADR-0005），不允许各取各的。 */
data class ChangeClock(val now: Instant, val zone: ZoneId)

/**
 * 时钟来源：组合根在每次派发前取最新快照，测试注入固定值。
 * 确认回调的派发（ConfirmationGate）共用本来源，保证自动级与确认级看到同一套时钟约定。
 */
fun interface ChangeClockSource {
    fun snapshot(): ChangeClock
}
