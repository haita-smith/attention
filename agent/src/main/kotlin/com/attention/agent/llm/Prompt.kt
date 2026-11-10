package com.attention.agent.llm

import com.attention.agent.AgentEvent
import com.attention.agent.AgentTool
import com.attention.agent.ChangeTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 一条 chat 消息。角色只有 system / user 两种：本客户端不保存对话，每次请求都是无状态重放。 */
@Serializable
data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
    }
}

/** 送进 prompt 的工具描述：只包含模型需要知道的三个事实，不暴露执行细节。 */
@Serializable
internal data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * 把「请求 + 本次运行的事件轨迹 + 可用工具」拼成 chat 消息。
 *
 * 隐私最小化的落点就在这里：请求是用户这次说的话，轨迹是本运行真实发生的思考、
 * 工具调用与工具返回；**没有任何全量领域状态**。模型想知道别的，必须通过只读工具去查，
 * 而那也是本次运行真实调用、真实返回的结果。
 *
 * 输出协议是严格的 JSON：模型每轮只能给一个对象，要么 `action` 要么 `answer`。
 * 非法输出由 [OpenAiCompatReasoner] 收敛为失败终态，不做猜测式容错。
 */
internal object AgentPrompt {
    private val json = Json { encodeDefaults = true }

    /** 只读工具与变更工具共用一个命名空间，清单按注册顺序拼接，便于 prompt 稳定复现。 */
    fun descriptors(readOnly: List<AgentTool>, change: List<ChangeTool>): List<ToolDescriptor> =
        readOnly.map { ToolDescriptor(it.name, it.description, it.parametersSchema) } +
            change.map { ToolDescriptor(it.name, it.description, it.parametersSchema) }

    fun messages(
        request: String,
        history: List<AgentEvent>,
        tools: List<ToolDescriptor>,
    ): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.SYSTEM, systemPrompt(tools)),
        ChatMessage(ChatMessage.USER, userMessage(request, history)),
    )

    fun systemPrompt(tools: List<ToolDescriptor>): String = buildString {
        appendLine("你是「注意力」App 内置的时间管理助手。你帮助用户查询和修改目标、日程、时间记录与计时。")
        appendLine()
        appendLine("## 可用工具")
        appendLine(renderTools(tools))
        appendLine()
        appendLine("## 回复格式")
        appendLine("每次回复必须是一个 JSON 对象，不要输出任何其他文字、Markdown 代码块或注释。")
        append("""- 需要调用工具：{"thought": "为什么调用它", "action": {"tool": "工具名", "args": {}}}""")
        appendLine()
        append("""- 可以回答用户：{"thought": "可选", "answer": "给用户的答复"}""")
        appendLine()
        appendLine("一轮只调用一个工具；工具执行结果会出现在下一条消息里。")
        appendLine()
        appendLine("## 规则")
        appendLine("1. 只能使用「可用工具」里列出的工具名与参数，不要编造。")
        appendLine("2. 参数必须符合对应工具的 JSON Schema；信息不足时先向用户确认。")
        appendLine("3. 不要编造数据；需要的事实用工具查询。")
        appendLine("4. 用中文回答用户。")
        appendLine("5. 用户没有明确要求的破坏性操作不要执行。")
    }

    fun userMessage(request: String, history: List<AgentEvent>): String = buildString {
        appendLine("用户请求：")
        appendLine(request)
        appendLine()
        appendLine("本次运行已发生的事件（按时间顺序，JSON 数组；可能为空）：")
        append(encodeHistory(history))
    }

    private fun renderTools(tools: List<ToolDescriptor>): String =
        json.encodeToString(
            ListSerializer(ToolDescriptor.serializer()),
            tools,
        )

    private fun encodeHistory(history: List<AgentEvent>): String =
        json.encodeToString(ListSerializer(AgentEvent.serializer()), history)

    /** 粗略估算：4 个字符约 1 个 token。只用于预算控制，不追求精确。 */
    fun estimateTokens(text: String): Int = maxOf(1, (text.length + 3) / 4)

    /** 一整次请求的 prompt 估算（system + user），预算按它累加。 */
    fun estimateTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateTokens(it.content) }
}

