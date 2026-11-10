package com.attention.agent

/**
 * 一次运行的 prompt token 预算被用尽。
 *
 * 放在循环这一层而不是模型客户端里：预算耗尽与步数耗尽同属**循环的预算语义**，
 * 都要收敛为 [AgentOutcome.BudgetExhausted]；模型客户端只是产生这个事实的地方。
 * 这样循环就不必认识任何具体的推理器实现。
 */
class TokenBudgetExceededException(
    val estimatedTokens: Int,
    val budget: Int,
) : Exception("本次运行累计 prompt 估算 $estimatedTokens token，超过预算 $budget")
