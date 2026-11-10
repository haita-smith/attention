package com.attention.agent.llm

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatConfigTest {

    private fun propertiesFile(content: String): File =
        Files.createTempFile("attention-llm", ".properties").toFile().apply {
            writeText(content)
            deleteOnExit()
        }

    private val env = mapOf(
        OpenAiCompatConfigLoader.ENV_BASE_URL to "https://api.example.com/v1",
        OpenAiCompatConfigLoader.ENV_MODEL to "gpt-test",
        OpenAiCompatConfigLoader.ENV_API_KEY to "sk-secret-1234",
    )

    @Test
    fun environment_variables_alone_are_enough() {
        val config = requireNotNull(OpenAiCompatConfigLoader.load(environment = env, propertiesFile = null))

        assertEquals("https://api.example.com/v1", config.baseUrl)
        assertEquals("gpt-test", config.model)
        assertEquals("sk-secret-1234", config.apiKey)
        assertEquals(OpenAiCompatConfig.DEFAULT_PROMPT_TOKEN_BUDGET, config.promptTokenBudget)
    }

    @Test
    fun local_properties_fills_in_when_environment_is_empty() {
        val file = propertiesFile(
            """
            llm.baseUrl=https://local.example.com/v1
            llm.model=local-model
            llm.apiKey=local-key
            llm.promptTokenBudget=5000
            """.trimIndent(),
        )

        val config = requireNotNull(OpenAiCompatConfigLoader.load(environment = emptyMap(), propertiesFile = file))

        assertEquals("https://local.example.com/v1", config.baseUrl)
        assertEquals("local-model", config.model)
        assertEquals("local-key", config.apiKey)
        assertEquals(5000, config.promptTokenBudget)
    }

    @Test
    fun environment_wins_over_properties_but_properties_can_fill_the_gaps() {
        val file = propertiesFile(
            """
            llm.baseUrl=https://from-file.example.com/v1
            llm.apiKey=file-key
            """.trimIndent(),
        )
        val partialEnv = mapOf(
            OpenAiCompatConfigLoader.ENV_BASE_URL to "https://from-env.example.com/v1",
            OpenAiCompatConfigLoader.ENV_MODEL to "env-model",
        )

        val config = requireNotNull(OpenAiCompatConfigLoader.load(environment = partialEnv, propertiesFile = file))

        assertEquals("https://from-env.example.com/v1", config.baseUrl)
        assertEquals("env-model", config.model)
        assertEquals("file-key", config.apiKey)
    }

    @Test
    fun a_missing_required_value_means_not_configured() {
        assertNull(
            OpenAiCompatConfigLoader.load(
                environment = env - OpenAiCompatConfigLoader.ENV_API_KEY,
                propertiesFile = null,
            ),
        )
    }

    @Test
    fun blank_values_are_treated_as_absent() {
        val file = propertiesFile("llm.model=file-model")

        val config = requireNotNull(
            OpenAiCompatConfigLoader.load(
                environment = mapOf(
                    OpenAiCompatConfigLoader.ENV_BASE_URL to "https://api.example.com/v1",
                    OpenAiCompatConfigLoader.ENV_MODEL to "   ",
                    OpenAiCompatConfigLoader.ENV_API_KEY to "sk-key",
                ),
                propertiesFile = file,
            ),
        )

        assertEquals("file-model", config.model)
    }

    @Test
    fun an_unparsable_budget_falls_back_to_the_default() {
        val config = requireNotNull(
            OpenAiCompatConfigLoader.load(
                environment = env + (OpenAiCompatConfigLoader.ENV_PROMPT_TOKEN_BUDGET to "not-a-number"),
                propertiesFile = null,
            ),
        )

        assertEquals(OpenAiCompatConfig.DEFAULT_PROMPT_TOKEN_BUDGET, config.promptTokenBudget)
    }

    @Test
    fun the_same_configuration_twice_produces_equal_values() {
        val first = requireNotNull(OpenAiCompatConfigLoader.load(environment = env, propertiesFile = null))
        val second = requireNotNull(OpenAiCompatConfigLoader.load(environment = env, propertiesFile = null))

        assertEquals(first, second)
    }

    @Test
    fun toString_never_reveals_the_api_key() {
        val config = requireNotNull(OpenAiCompatConfigLoader.load(environment = env, propertiesFile = null))

        assertFalse(config.toString().contains("sk-secret-1234"))
        assertTrue(config.toString().contains("1234"))
        assertFalse(config.redactedApiKey.contains("secret"))
    }

    @Test
    fun a_short_key_is_fully_masked() {
        val config = OpenAiCompatConfig(baseUrl = "https://x", model = "m", apiKey = "abc")

        assertEquals("****", config.redactedApiKey)
        assertFalse(config.toString().contains("abc"))
    }

    @Test
    fun the_endpoint_is_joined_without_double_slashes() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiCompatConfig(baseUrl = "https://api.example.com/v1/", model = "m", apiKey = "k").chatCompletionsUrl,
        )
        assertEquals(
            "https://api.example.com/chat/completions",
            OpenAiCompatConfig(baseUrl = "https://api.example.com", model = "m", apiKey = "k").chatCompletionsUrl,
        )
    }

    @Test
    fun an_invalid_configuration_is_rejected_at_construction() {
        listOf(
            { OpenAiCompatConfig(baseUrl = "", model = "m", apiKey = "k") },
            { OpenAiCompatConfig(baseUrl = "https://x", model = " ", apiKey = "k") },
            { OpenAiCompatConfig(baseUrl = "https://x", model = "m", apiKey = "") },
            { OpenAiCompatConfig(baseUrl = "https://x", model = "m", apiKey = "k", promptTokenBudget = 0) },
        ).forEach { build ->
            assertTrue(runCatching { build() }.isFailure)
        }
    }
}
