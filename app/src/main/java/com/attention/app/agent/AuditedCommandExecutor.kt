package com.attention.app.agent

import com.attention.agent.CommandExecutor
import com.attention.app.data.audit.AiChangeAudit
import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import java.time.Instant
import java.time.ZoneId

/**
 * 审计与快照的写入点(ADR-0009):包装既有派发通道的 [CommandExecutor]。
 *
 * 不变式有三条:
 * - 每条变更 command 派发**前**调 [AiChangeAudit.beforeChange],快照的是
 *   [currentState] 在那一刻的现值;快照写失败即本次变更失败——异常原样上行、
 *   派发中止,绝不写出「没有存档的 AI 变更」。异常如何收敛(错误观测或
 *   Failed 终态)由 `:agent` 的 P0 契约决定,这里不做吞咽。
 * - 运行边界内逐次收集领域 command 的简单类名:executor 是唯一准确观测点,
 *   「这次运行提交过什么」以账本口径记录;名单不去重,去重归审计门面。
 * - 派发只有成功落地才算提交:通道故障的那一次不入名单。
 *
 * [beginRun]/[finishRun] 由 [WorkspaceAgentRuntime] 在运行边界成对调用;
 * 边界外的派发(手动路径不经此通道,确认门的在途裁决)不进任何名单。
 */
class AuditedCommandExecutor(
    private val delegate: CommandExecutor,
    private val audit: AiChangeAudit,
    private val currentState: () -> AttentionState,
) : CommandExecutor {

    private val lock = Any()
    private var boundaryNames: MutableList<String>? = null

    /** 开启一次运行的收集边界;上一边界未关闭即装配错误,立即失败。 */
    fun beginRun() {
        synchronized(lock) {
            check(boundaryNames == null) { "上一次运行的收集边界尚未关闭" }
            boundaryNames = mutableListOf()
        }
    }

    /** 关闭收集边界并返回本次运行提交过的 command 简单类名(按派发顺序,不去重)。 */
    fun finishRun(): List<String> =
        synchronized(lock) {
            val names = boundaryNames ?: return@synchronized emptyList()
            boundaryNames = null
            names.toList()
        }

    override suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult {
        audit.beforeChange(currentState())
        val result = delegate.execute(command, now, zone)
        synchronized(lock) { boundaryNames?.add(command::class.java.simpleName) }
        return result
    }
}
