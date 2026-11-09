package com.attention.app.agent

import com.attention.agent.AgentEvent
import com.attention.agent.AgentOutcome
import com.attention.agent.ConfirmationToken
import com.attention.app.data.audit.AgentRunOutcome
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 结局映射的契约:领域终态与审计存储枚举一一对应(spec Testing Decisions),
 * 领域类型不渗进存储格式。待确认终态没有存储侧对应——降级开关关闭时不可达,
 * 映射对它显式失败,不给未来的确认线留一条静默错记的路。
 */
class AgentOutcomeMappingTest {

    private val trace = listOf<AgentEvent>(AgentEvent.Final("答复"))

    @Test
    fun answered_maps_to_answered() {
        assertEquals(AgentRunOutcome.ANSWERED, AgentOutcome.Answered("答复", trace).toAgentRunOutcome())
    }

    @Test
    fun budget_exhausted_maps_to_budget_exhausted() {
        assertEquals(AgentRunOutcome.BUDGET_EXHAUSTED, AgentOutcome.BudgetExhausted(trace).toAgentRunOutcome())
    }

    @Test
    fun failed_maps_to_failed() {
        assertEquals(AgentRunOutcome.FAILED, AgentOutcome.Failed("推理器失败", trace).toAgentRunOutcome())
    }

    @Test
    fun pending_confirmation_has_no_storage_outcome_and_fails_explicitly() {
        assertThrows(IllegalStateException::class.java) {
            AgentOutcome.PendingConfirmation(
                token = ConfirmationToken("1"),
                preview = JsonObject(emptyMap()),
                trace = trace,
            ).toAgentRunOutcome()
        }
    }
}
