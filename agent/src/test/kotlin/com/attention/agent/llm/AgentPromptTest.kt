package com.attention.agent.llm

import com.attention.agent.AgentEvent
import com.attention.agent.AgentTool
import com.attention.agent.ChangeTool
import com.attention.agent.RiskLevel
import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTest {

    private class FakeReadTool(
        override val name: String,
        override val description: String,
    ) : AgentTool {
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("period", buildJsonObject { put("type", "string") }) })
        }

        override fun execute(args: JsonObject): JsonObject = buildJsonObject { put("ok", true) }
    }

    private class FakeChangeTool(
        override val name: String,
        override val description: String,
    ) : ChangeTool {
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("title", buildJsonObject { put("type", "string") }) })
        }
        override val riskLevels: Set<RiskLevel> = setOf(RiskLevel.Auto)

        override fun riskLevel(args: JsonObject): RiskLevel = RiskLevel.Auto
        override fun command(args: JsonObject): AttentionCommand = AttentionCommand.StopTimer
        override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject =
            buildJsonObject { put("done", true) }
    }

    private val tools = AgentPrompt.descriptors(
        readOnly = listOf(FakeReadTool("query_targets", "查询目标进度")),
        change = listOf(FakeChangeTool("record_time", "记录一段实际投入")),
    )

    @Test
    fun descriptors_keep_registration_order_across_both_tool_families() {
        assertEquals(listOf("query_targets", "record_time"), tools.map { it.name })
        assertEquals("查询目标进度", tools.first().description)
        assertTrue(tools.first().parameters.containsKey("properties"))
    }

    @Test
    fun the_system_prompt_lists_every_tool_with_its_schema() {
        val system = AgentPrompt.systemPrompt(tools)

        assertTrue(system.contains("query_targets"))
        assertTrue(system.contains("record_time"))
        assertTrue(system.contains("查询目标进度"))
        assertTrue(system.contains("\"parameters\""))
        assertTrue(system.contains("JSON"))
    }

    @Test
    fun the_request_is_exactly_two_messages_system_then_user() {
        val messages = AgentPrompt.messages("帮我记 25 分钟", emptyList(), tools)

        assertEquals(2, messages.size)
        assertEquals(ChatMessage.SYSTEM, messages[0].role)
        assertEquals(ChatMessage.USER, messages[1].role)
    }

    @Test
    fun the_user_message_carries_the_request_and_an_empty_history() {
        val user = AgentPrompt.messages("帮我记 25 分钟", emptyList(), tools).last().content

        assertTrue(user.contains("帮我记 25 分钟"))
        assertTrue(user.trimEnd().endsWith("[]"))
    }

    @Test
    fun the_history_is_serialized_in_order_and_keeps_errors_marked() {
        val history = listOf(
            AgentEvent.Thought("先查一下目标"),
            AgentEvent.Action("query_targets", buildJsonObject { put("period", "today") }),
            AgentEvent.Observation(buildJsonObject { put("error", "参数不合法") }, isError = true),
            AgentEvent.Final("抱歉，我再试一次"),
        )

        val user = AgentPrompt.messages("查一下今天", history, tools).last().content

        val thoughtAt = user.indexOf("先查一下目标")
        val actionAt = user.indexOf("query_targets")
        val observationAt = user.indexOf("参数不合法")
        val finalAt = user.indexOf("抱歉，我再试一次")
        assertTrue(thoughtAt in 0 until actionAt)
        assertTrue(actionAt < observationAt)
        assertTrue(observationAt < finalAt)
        assertTrue(user.contains("\"isError\":true"))
    }

    @Test
    fun the_prompt_never_carries_any_domain_state_beyond_the_request_and_history() {
        // 领域里存在「机密目标」，但本模块根本拿不到状态：prompt 里不该出现它。
        val user = AgentPrompt.messages("今天做了什么", emptyList(), tools)
            .joinToString("\n") { it.content }
        val system = AgentPrompt.systemPrompt(tools)

        assertFalse(user.contains("机密目标"))
        assertFalse(system.contains("机密目标"))
        assertFalse(user.contains("experience"))
    }

    @Test
    fun token_estimation_is_roughly_a_quarter_of_the_text_length() {
        assertEquals(1, AgentPrompt.estimateTokens(""))
        assertEquals(1, AgentPrompt.estimateTokens("abc"))
        assertEquals(25, AgentPrompt.estimateTokens("a".repeat(100)))
        assertEquals(
            AgentPrompt.estimateTokens("abc") + AgentPrompt.estimateTokens("defgh"),
            AgentPrompt.estimateTokens(
                listOf(ChatMessage(ChatMessage.SYSTEM, "abc"), ChatMessage(ChatMessage.USER, "defgh")),
            ),
        )
    }
}
