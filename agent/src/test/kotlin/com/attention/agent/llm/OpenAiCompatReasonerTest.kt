package com.attention.agent.llm

import com.attention.agent.AgentEvent
import com.attention.agent.AgentTool
import com.attention.agent.ReasonerDecision
import com.attention.agent.TokenBudgetExceededException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatReasonerTest {
    private lateinit var server: MockWebServer

    private class QueryTool : AgentTool {
        override val name: String = "query_targets"
        override val description: String = "查询目标进度"
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("period", buildJsonObject { put("type", "string") }) })
        }

        override fun execute(args: JsonObject): JsonObject = buildJsonObject { put("ok", true) }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(
        budget: Int = 100_000,
        readTimeoutMillis: Long = 5_000,
        connectTimeoutMillis: Long = 5_000,
    ) = OpenAiCompatConfig(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = "test-model",
        apiKey = "sk-secret-1234",
        promptTokenBudget = budget,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
    )

    private fun completion(content: String): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
                        put("finish_reason", "stop")
                    },
                )
            },
        )
    }.toString()

    private fun reasoner(
        budget: Int = 100_000,
        timeoutMillis: Long = 5_000,
    ) = OpenAiCompatReasoner(
        config = config(budget = budget, readTimeoutMillis = timeoutMillis),
        readOnlyTools = listOf(QueryTool()),
    )

    @Test
    fun an_action_reply_becomes_an_act_decision() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                completion("""{"thought":"先查目标","action":{"tool":"query_targets","args":{"period":"today"}}}"""),
            ),
        )

        val decision = reasoner().reason("今天的目标进度如何", emptyList())

        val act = decision as ReasonerDecision.Act
        assertEquals("query_targets", act.action.tool)
        assertEquals("先查目标", act.thought)
    }

    @Test
    fun an_answer_reply_becomes_an_answer_decision() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("""{"answer":"今天已投入 40 分钟"}""")))

        val answer = reasoner().reason("今天怎么样", emptyList()) as ReasonerDecision.Answer

        assertEquals("今天已投入 40 分钟", answer.text)
    }

    @Test
    fun the_request_only_carries_the_prompt_material_and_never_the_api_key_in_the_body() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("""{"answer":"好的"}""")))

        reasoner().reason("帮我看看雅思", emptyList())

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()

        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-secret-1234", recorded.getHeader("Authorization"))
        assertTrue(body.contains("test-model"))
        assertTrue(body.contains("帮我看看雅思"))
        assertTrue(body.contains("query_targets"))
        assertTrue(body.contains("查询目标进度"))
        // 密钥只走请求头，绝不进请求体
        assertFalse(body.contains("sk-secret-1234"))
        // 全量领域状态没有进入 prompt：只有请求、轨迹与工具清单
        assertFalse(body.contains("机密目标"))
    }

    @Test
    fun an_http_error_becomes_a_failure_value_with_the_status_code() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val failure = failureOf { reasoner().reason("请求", emptyList()) }

        assertTrue(failure is OpenAiCompatFailureException)
        assertTrue(failure.message.orEmpty().contains("500"))
    }

    @Test
    fun a_rate_limit_is_reported_as_a_failure_value() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate limited"}"""))

        val failure = failureOf { reasoner().reason("请求", emptyList()) }

        assertTrue(failure is OpenAiCompatFailureException)
        assertTrue(failure.message.orEmpty().contains("429"))
    }

    @Test
    fun a_body_that_is_not_json_is_rejected() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>gateway error</html>"))

        val failure = failureOf { reasoner().reason("请求", emptyList()) }

        assertTrue(failure is InvalidModelResponseException)
    }

    @Test
    fun a_response_without_choices_is_rejected() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))

        val failure = failureOf { reasoner().reason("请求", emptyList()) }

        assertTrue(failure is InvalidModelResponseException)
    }

    @Test
    fun an_empty_body_is_rejected() = runBlocking {
        server.enqueue(MockResponse().setBody(""))

        val failure = failureOf { reasoner().reason("请求", emptyList()) }

        assertTrue(failure is InvalidModelResponseException)
    }

    @Test
    fun a_timeout_becomes_a_failure_value() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val failure = failureOf { reasoner(timeoutMillis = 500).reason("请求", emptyList()) }

        assertTrue(failure is OpenAiCompatFailureException)
        assertTrue(failure.message.orEmpty().contains("网络请求失败"))
    }

    @Test
    fun the_token_budget_stops_the_request_before_it_is_sent() = runBlocking {
        val failure = failureOf { reasoner(budget = 1).reason("请求", emptyList()) }

        assertTrue(failure is TokenBudgetExceededException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun the_budget_accumulates_across_the_steps_of_one_run() = runBlocking {
        val tools = AgentPrompt.descriptors(listOf(QueryTool()), emptyList())
        val firstStepEstimate = AgentPrompt.estimateTokens(AgentPrompt.messages("请求", emptyList(), tools))
        server.enqueue(MockResponse().setBody(completion("""{"answer":"好的"}""")))
        val subject = reasoner(budget = firstStepEstimate)

        // 第一步刚好在预算内
        subject.reason("请求", emptyList())

        // 第二步轨迹变长，估算超过同一次运行的累计上限：不发请求
        val failure = failureOf {
            subject.reason("请求", listOf(AgentEvent.Thought("第一轮")))
        }

        assertTrue(failure is TokenBudgetExceededException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun failures_never_mention_the_api_key() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        val failure = failureOf { reasoner().reason("请求", emptyList()) }

        assertFalse(failure.message.orEmpty().contains("sk-secret-1234"))
    }

    @Test
    fun a_second_run_starts_its_own_budget() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("""{"answer":"一"}""")))
        server.enqueue(MockResponse().setBody(completion("""{"answer":"二"}""")))
        val tools = AgentPrompt.descriptors(listOf(QueryTool()), emptyList())
        val firstStepEstimate = AgentPrompt.estimateTokens(AgentPrompt.messages("请求", emptyList(), tools))
        val subject = reasoner(budget = firstStepEstimate)

        subject.reason("请求", emptyList())
        // 新一次运行以空轨迹开始，预算重新计算，因此还能再发一次
        val answer = subject.reason("请求", emptyList()) as ReasonerDecision.Answer

        assertEquals("二", answer.text)
        assertEquals(2, server.requestCount)
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        val thrown = runCatching { block() }.exceptionOrNull()
        return requireNotNull(thrown) { "预期失败但调用成功了" }
    }
}
