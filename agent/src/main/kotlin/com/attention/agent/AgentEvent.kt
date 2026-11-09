package com.attention.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 一次助手运行的事件轨迹，扁平追加：思考、行动、观测、最终答复按发生顺序排列。
 *
 * 「按轮分组」（思考+行动+观测为一轮）只是展示视角，由消费方自行转换；
 * 轨迹本身只保留一种真相，消费方各自发明的分组不进数据模型。
 * 动作参数与观测载荷统一用 [JsonObject]：下一阶段 LLM 绑定可以直接序列化，
 * 本阶段的工具测试也能直接构造，不为本阶段引入类型化参数表。
 */
@Serializable
sealed class AgentEvent {
    /** 推理器在行动前写下的思考。 */
    @Serializable
    @SerialName("thought")
    data class Thought(val text: String) : AgentEvent()

    /** 一次工具调用：调用哪个工具、带什么参数。 */
    @Serializable
    @SerialName("action")
    data class Action(val tool: String, val args: JsonObject) : AgentEvent()

    /** 工具执行结果。[isError] 为真表示工具侧失败，载荷是给推理器看的错误说明。 */
    @Serializable
    @SerialName("observation")
    data class Observation(val payload: JsonObject, val isError: Boolean = false) : AgentEvent()

    /**
     * 确认级动作已受理但未执行：记录待确认的工具、参数与令牌，循环到此短路。
     * command 是否真正派发由用户裁决决定，观测不存在——没有执行就没有可观测的结果。
     */
    @Serializable
    @SerialName("pending_confirmation")
    data class PendingConfirmation(val tool: String, val args: JsonObject, val token: ConfirmationToken) : AgentEvent()

    /** 推理器给出的最终答复，循环到此结束。 */
    @Serializable
    @SerialName("final")
    data class Final(val text: String) : AgentEvent()
}
