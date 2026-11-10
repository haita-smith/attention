package com.attention.agent.llm

import com.attention.agent.ActionRequest
import com.attention.agent.ReasonerDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型回复不能翻译成决策时抛这个。它属于「推理器自身失败」：循环收敛为
 * Failed 终态，不做猜测式容错——猜错的工具调用比一句「我失败了」更糟。
 */
class InvalidModelResponseException(message: String) : Exception(message)

/**
 * 把模型的一条回复翻译成 [ReasonerDecision]。
 *
 * 协议只有一个 JSON 对象，二选一：
 * - `{"thought": "...", "action": {"tool": "...", "args": {...}}}`
 * - `{"thought": "...", "answer": "..."}`
 *
 * 只容忍一种常见偏差：整体包在 Markdown 代码块里（模型很爱这么干，剥掉围栏
 * 不影响语义）。除此之外一律失败：两个字段都给、都不给、字段类型不对、不是 JSON，
 * 都不猜、不截断、不把原文塞进异常消息（原文可能包含用户数据，不该进日志）。
 */
internal object DecisionParser {
    private val json = Json

    fun parse(raw: String): ReasonerDecision {
        val text = stripCodeFence(raw).trim()
        if (text.isEmpty()) throw InvalidModelResponseException("模型回复为空")

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw InvalidModelResponseException("模型回复不是 JSON 对象")

        val thought = (root["thought"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content

        val action = root["action"]
        val answer = root["answer"]

        return when {
            action != null && answer != null ->
                throw InvalidModelResponseException("模型回复同时包含 action 与 answer")

            action is JsonObject -> parseAction(action, thought)

            answer is JsonPrimitive && answer.isString ->
                ReasonerDecision.Answer(thought = thought, text = answer.content)

            else -> throw InvalidModelResponseException("模型回复既没有 action 也没有 answer")
        }
    }

    private fun parseAction(action: JsonObject, thought: String?): ReasonerDecision.Act {
        val tool = (action["tool"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.trim()
        if (tool.isNullOrEmpty()) throw InvalidModelResponseException("action.tool 缺失或不是字符串")

        val args = action["args"] as? JsonObject ?: JsonObject(emptyMap())
        return ReasonerDecision.Act(thought = thought.orEmpty(), action = ActionRequest(tool, args))
    }

    /** 去掉整体包裹的 ``` 代码块（含语言标记），只此一种容错。 */
    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpen = trimmed.removePrefix("```")
        val body = withoutOpen.substringAfter('\n', withoutOpen)
        return body.substringBeforeLast("```").trim()
    }
}
