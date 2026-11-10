package com.attention.agent.llm

import com.attention.agent.ReasonerDecision
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionParserTest {

    @Test
    fun an_action_becomes_an_act_decision_with_thought_and_args() {
        val decision = DecisionParser.parse(
            """{"thought":"先建个目标","action":{"tool":"manage_target","args":{"action":"create","title":"雅思"}}}""",
        )

        val act = decision as ReasonerDecision.Act
        assertEquals("先建个目标", act.thought)
        assertEquals("manage_target", act.action.tool)
        assertEquals("create", (act.action.args["action"] as JsonPrimitive).content)
    }

    @Test
    fun an_action_without_args_gets_an_empty_object() {
        val act = DecisionParser.parse("""{"action":{"tool":"query_timer"}}""") as ReasonerDecision.Act

        assertEquals(JsonObject(emptyMap()), act.action.args)
        assertEquals("", act.thought)
    }

    @Test
    fun an_answer_becomes_an_answer_decision_and_keeps_the_thought() {
        val answer = DecisionParser.parse("""{"thought":"查完了","answer":"今天已经投入 40 分钟"}""")
            as ReasonerDecision.Answer

        assertEquals("查完了", answer.thought)
        assertEquals("今天已经投入 40 分钟", answer.text)
    }

    @Test
    fun an_answer_may_omit_the_thought() {
        val answer = DecisionParser.parse("""{"answer":"好的"}""") as ReasonerDecision.Answer

        assertNull(answer.thought)
        assertEquals("好的", answer.text)
    }

    @Test
    fun a_markdown_wrapped_json_object_is_still_accepted() {
        val decision = DecisionParser.parse(
            """
            ```json
            {"answer":"收到"}
            ```
            """.trimIndent(),
        )

        assertEquals("收到", (decision as ReasonerDecision.Answer).text)
    }

    @Test
    fun granting_both_action_and_answer_is_rejected() {
        assertRejected("""{"action":{"tool":"x"},"answer":"y"}""")
    }

    @Test
    fun granting_neither_action_nor_answer_is_rejected() {
        assertRejected("""{"thought":"我还没想好"}""")
    }

    @Test
    fun a_non_json_reply_is_rejected() {
        assertRejected("好的，我这就去办")
    }

    @Test
    fun an_empty_reply_is_rejected() {
        assertRejected("   ")
    }

    @Test
    fun an_action_without_a_string_tool_is_rejected() {
        assertRejected("""{"action":{"tool":42}}""")
        assertRejected("""{"action":{"tool":""}}""")
        assertRejected("""{"action":{}}""")
    }

    @Test
    fun a_non_string_answer_is_rejected() {
        assertRejected("""{"answer":42}""")
    }

    @Test
    fun a_json_array_reply_is_rejected() {
        assertRejected("""[{"answer":"x"}]""")
    }

    private fun assertRejected(raw: String) {
        val thrown = runCatching { DecisionParser.parse(raw) }.exceptionOrNull()
        assertTrue("应当拒绝：$raw", thrown is InvalidModelResponseException)
    }
}
