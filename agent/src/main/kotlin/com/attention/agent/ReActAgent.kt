package com.attention.agent

import java.util.concurrent.CancellationException

// JVM 上 kotlinx.coroutines.CancellationException 就是本文件导入的 JDK 类型的别名：
// 捕获它即可接住协程取消，又不必让纯投影模块拉进 coroutines 依赖（与 :domain/:workspace-state 一致）。

/**
 * ReAct 循环：请求进，终态出。每一轮把推理器的决策落成事件——思考、行动、观测
 * ——并把观测回填给下一轮推理，直到答复、预算耗尽或推理器失败。
 *
 * 这是本模块的深模块：对外只有 [run] 一个入口，两个依赖（推理器、工具表）都是
 * 单方法接口；事件建模、错误吸收、预算控制全部藏在循环里，
 * 调用方与测试只面对终态和轨迹（ADR-0008）。
 */
class ReActAgent(
    private val reasoner: Reasoner,
    private val tools: ToolCatalog,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    init {
        require(maxSteps > 0) { "maxSteps 必须为正，实际 $maxSteps" }
    }

    /**
     * 跑一次循环。除协程取消外不抛任何异常：推理器自身失败收敛为
     * [AgentOutcome.Failed]；工具侧的一切失败（未知工具、参数不合法、执行异常）
     * 都收敛为带 isError 的观测回填给推理器，由它决定纠正、换路还是直接答复；
     * 只有变更执行通道（[CommandExecutor]）的故障直接收敛为 [AgentOutcome.Failed]——
     * 那不是推理器换个问法能修复的。
     */
    suspend fun run(request: String): AgentOutcome {
        val trace = mutableListOf<AgentEvent>()
        repeat(maxSteps) {
            val decision = try {
                reasoner.reason(request, trace.toList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return AgentOutcome.Failed(describeFailure(failure), trace)
            }
            when (decision) {
                is ReasonerDecision.Act -> {
                    trace += AgentEvent.Thought(decision.thought)
                    when (val execution = executeAction(decision.action)) {
                        is ToolExecution.Observed -> {
                            trace += AgentEvent.Action(decision.action.tool, decision.action.args)
                            trace += execution.observation
                        }
                        is ToolExecution.DispatchFailed -> {
                            trace += AgentEvent.Action(decision.action.tool, decision.action.args)
                            return AgentOutcome.Failed(execution.reason, trace)
                        }
                    }
                }
                is ReasonerDecision.Answer -> {
                    decision.thought?.let { trace += AgentEvent.Thought(it) }
                    trace += AgentEvent.Final(decision.text)
                    return AgentOutcome.Answered(decision.text, trace)
                }
            }
        }
        return AgentOutcome.BudgetExhausted(trace)
    }

    /** 一次行动统一经目录挂起执行；工具侧的一切失败在这里收敛为错误观测（协程取消除外）。 */
    private suspend fun executeAction(action: ActionRequest): ToolExecution =
        try {
            tools.execute(action.tool, action.args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ToolExecution.Observed(errorObservation(describeFailure(failure)))
        }

    companion object {
        const val DEFAULT_MAX_STEPS = 8
    }
}
