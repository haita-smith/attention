package com.attention.agent.llm

import java.io.File
import java.util.Properties

/**
 * 一次模型接入的静态配置：端点、模型名与密钥。
 *
 * 密钥只存在于内存与本类实例里：它不进日志（[toString] 已脱敏）、不进异常消息、
 * 不进备份导出——备份只序列化领域状态，而配置根本不在领域状态里。
 * 开发期约定从环境变量或 `local.properties` 读取，见 [OpenAiCompatConfigLoader]。
 */
data class OpenAiCompatConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    /** 单次运行累计 prompt token 上限；默认 100,000。 */
    val promptTokenBudget: Int = DEFAULT_PROMPT_TOKEN_BUDGET,
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl 不能为空" }
        require(model.isNotBlank()) { "model 不能为空" }
        require(apiKey.isNotBlank()) { "apiKey 不能为空" }
        require(promptTokenBudget > 0) { "promptTokenBudget 必须为正，实际 $promptTokenBudget" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis 必须为正，实际 $connectTimeoutMillis" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis 必须为正，实际 $readTimeoutMillis" }
    }

    /** 端点补齐 `/chat/completions`；用户把 baseUrl 写成带尾斜杠或不带都能用。 */
    val chatCompletionsUrl: String
        get() = baseUrl.trimEnd('/') + "/chat/completions"

    /** 给日志与错误消息看的密钥：只留尾四位，其余不可见。 */
    val redactedApiKey: String
        get() = if (apiKey.length <= 4) "****" else "****" + apiKey.takeLast(4)

    /** 显式覆盖，避免 data class 自动生成的 toString 把密钥原样带进日志。 */
    override fun toString(): String =
        "OpenAiCompatConfig(baseUrl=$baseUrl, model=$model, apiKey=$redactedApiKey, " +
            "promptTokenBudget=$promptTokenBudget)"

    companion object {
        const val DEFAULT_PROMPT_TOKEN_BUDGET = 100_000
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_READ_TIMEOUT_MILLIS = 60_000L
    }
}

/**
 * 配置来源合并：环境变量优先，`local.properties` 补缺；两项都缺的必需项视为
 * 「未配置」（返回 null），由组合根决定是禁用助手还是报装配错误。
 *
 * 只做一次读取，结果不缓存：配置改了要重启进程，这是开发期约定，不是产品能力。
 */
object OpenAiCompatConfigLoader {
    const val ENV_BASE_URL = "ATTENTION_LLM_BASE_URL"
    const val ENV_MODEL = "ATTENTION_LLM_MODEL"
    const val ENV_API_KEY = "ATTENTION_LLM_API_KEY"
    const val ENV_PROMPT_TOKEN_BUDGET = "ATTENTION_LLM_PROMPT_TOKEN_BUDGET"

    const val PROPERTY_BASE_URL = "llm.baseUrl"
    const val PROPERTY_MODEL = "llm.model"
    const val PROPERTY_API_KEY = "llm.apiKey"
    const val PROPERTY_PROMPT_TOKEN_BUDGET = "llm.promptTokenBudget"

    /**
     * 读取配置。[environment] 可注入因此可在 JVM 测试里固定；
     * [propertiesFile] 为 null 时不做文件读取。三项必需配置缺任意一项即返回 null。
     */
    fun load(
        environment: Map<String, String> = System.getenv(),
        propertiesFile: File? = defaultPropertiesFile(),
    ): OpenAiCompatConfig? {
        val properties = propertiesFile
            ?.takeIf(File::isFile)
            ?.let(::readProperties)
            .orEmpty()

        fun pick(envKey: String, propertyKey: String): String? =
            environment[envKey]?.takeIf { it.isNotBlank() }
                ?: properties[propertyKey]?.takeIf { it.isNotBlank() }

        val baseUrl = pick(ENV_BASE_URL, PROPERTY_BASE_URL) ?: return null
        val model = pick(ENV_MODEL, PROPERTY_MODEL) ?: return null
        val apiKey = pick(ENV_API_KEY, PROPERTY_API_KEY) ?: return null
        val budget = pick(ENV_PROMPT_TOKEN_BUDGET, PROPERTY_PROMPT_TOKEN_BUDGET)
            ?.toIntOrNull()
            ?: OpenAiCompatConfig.DEFAULT_PROMPT_TOKEN_BUDGET

        return OpenAiCompatConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            promptTokenBudget = budget,
        )
    }

    /** 从当前目录向上找仓库根目录的 `local.properties`；找不到就返回该路径（读取时判定不存在）。 */
    fun defaultPropertiesFile(start: File = File("").absoluteFile): File =
        generateSequence(start) { it.parentFile }
            .map { File(it, "local.properties") }
            .firstOrNull { it.isFile }
            ?: File(start, "local.properties")

    private fun readProperties(file: File): Map<String, String> =
        runCatching {
            val properties = Properties()
            file.inputStream().use(properties::load)
            properties
        }.getOrNull()
            ?.entries
            ?.associate { it.key.toString() to it.value.toString() }
            .orEmpty()
}
