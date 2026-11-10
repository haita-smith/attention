package com.attention.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算耗尽属于循环的预算语义，不是推理器失败：它必须收敛为
 * [AgentOutcome.BudgetExhausted]，而不是 Failed。这条不变式由循环保证，
 * 推理器实现只需要抛出约定的异常。
 */
class TokenBudgetConvergenceTest {
    @Test
    fun a_reasoner_that_exhausts_the_token_budget_converges_to_budget_exhausted() = runBlocking {
        var calls = 0
        val reasoner = Reasoner { _, _ ->
            calls += 1
            throw TokenBudgetExceededException(estimatedTokens = 150, budget = 100)
        }
        val agent = ReActAgent(reasoner, ToolCatalog())

        val outcome = agent.run("帮我记 25 分钟")

        assertTrue(outcome is AgentOutcome.BudgetExhausted)
        assertEquals(1, calls)
    }
}
