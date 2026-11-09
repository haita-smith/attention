package com.attention.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 一次助手运行的终态。循环把所有失败都设计成结果值：调用方只需要处理这四种
 * 终态，不需要围绕 run 写 try/catch——「循环永不抛异常」是接口不变式
 * （协程取消除外，那表示调用方要停止，不是循环的失败）。
 * trace 随终态一起带走：无论怎么结束，发生了什么都有据可查。
 */
@Serializable
sealed class AgentOutcome {
    /** 推理器给出了最终答复。 */
    @Serializable
    @SerialName("answered")
    data class Answered(val text: String, val trace: List<AgentEvent>) : AgentOutcome()

    /** 步数预算耗尽仍未答复；trace 保留已发生的思考与观测供诊断。 */
    @Serializable
    @SerialName("budget_exhausted")
    data class BudgetExhausted(val trace: List<AgentEvent>) : AgentOutcome()

    /** 推理器自身失败（下一阶段即网络或模型故障）；trace 保留到失败前的全部事件。 */
    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String, val trace: List<AgentEvent>) : AgentOutcome()

    /**
     * 确认级动作已受理、等待用户裁决：command 未派发，循环以本终态短路收敛，
     * 不回填观测也不消耗后续步数。裁决经 [ConfirmationGate] 异步到达；
     * [preview] 是供确认卡片使用的预览载荷（将要发生什么），与 trace 解耦——
     * 卡片只看这一个载荷，不必解析轨迹。
     */
    @Serializable
    @SerialName("pending_confirmation")
    data class PendingConfirmation(
        val token: ConfirmationToken,
        val preview: JsonObject,
        val trace: List<AgentEvent>,
    ) : AgentOutcome()
}
