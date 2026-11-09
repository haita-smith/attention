package com.attention.app.agent

import com.attention.agent.AgentOutcome
import com.attention.app.data.audit.AgentRunOutcome

/**
 * 领域终态到审计存储枚举的映射(spec 裁决:映射写在 `:app` 侧包装层,
 * 领域类型不渗进存储格式)。三种可存档终态一一对应:
 * Answered/BudgetExhausted/Failed → ANSWERED/BUDGET_EXHAUSTED/FAILED。
 *
 * 待确认终态([AgentOutcome.PendingConfirmation])没有存储侧对应:组合根首版
 * 关闭确认通道(降级开关),该终态在当前装配下不可达。映射对它显式失败而不是
 * 挪用一个相近的枚举——接入确认卡片、翻开降级开关的工单必须先扩展这份映射
 * 与存储枚举,绝不静默错记(与 #226「宁可不可用,不可静默降级」同一姿态)。
 */
internal fun AgentOutcome.toAgentRunOutcome(): AgentRunOutcome = when (this) {
    is AgentOutcome.Answered -> AgentRunOutcome.ANSWERED
    is AgentOutcome.BudgetExhausted -> AgentRunOutcome.BUDGET_EXHAUSTED
    is AgentOutcome.Failed -> AgentRunOutcome.FAILED
    is AgentOutcome.PendingConfirmation -> error(
        "待确认终态没有对应的审计结局:确认通道开启前须先扩展映射与存储枚举",
    )
}
