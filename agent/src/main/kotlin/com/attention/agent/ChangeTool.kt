package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import kotlinx.serialization.json.JsonObject

/**
 * 变更动作的风险分级。分级是工具对自身的静态声明与逐次请求的运行期判定：
 * [Forbidden] 声明的工具根本进不了助手能力清单（构造期拒绝，与重名即败同一口径）；
 * [Confirmation] 的动作未经用户确认不得派发；[Auto] 即时执行，不被确认流程拖慢。
 */
enum class RiskLevel {
    /** 低风险、可自动执行的动作：补记时间、开始计时一类。 */
    Auto,

    /** 需要用户明确同意后才派发的动作；确认通道不可用时宁可报错也不静默转自动。 */
    Confirmation,

    /** 永禁操作（不可逆的破坏性动作）：任何请求都不得触达，注册即失败。 */
    Forbidden,
}

/**
 * 一个变更类工具。与只读 [AgentTool] 平行另立（ADR-0008：变更类动作不从只读接口扩展），
 * 执行分三相、由 [ToolCatalog] 统一编排为挂起执行：解析参数得到领域 command，
 * 经 [CommandExecutor] 派发，再把执行结果投影为观测载荷。
 *
 * command 解析独立成相是确认语义的前提：确认级动作在派发前就要把 command
 * 交给确认回调入口持有，而不是等「执行时再解析」。
 *
 * 解析与投影抛出的异常由循环转成错误观测回填，工具内部不需要自行把失败编码进返回值；
 * 但 [CommandExecutor] 的失败属于执行通道故障，由循环收敛为失败终态——那不是换个问法能修复的。
 */
interface ChangeTool {
    /** 注册名，与只读工具共用一个命名空间：推理器按它引用工具，重名在注册时失败。 */
    val name: String

    /** 一句话说明工具能改什么，进入 prompt。 */
    val description: String

    /** 参数的 JSON Schema（简版 object schema）。 */
    val parametersSchema: JsonObject

    /** 本工具全部 action 声明会用到的风险级别；含 [RiskLevel.Forbidden] 的工具不得注册。 */
    val riskLevels: Set<RiskLevel>

    /**
     * 由 args 判定本次请求的风险级别。意图级工具内可以按 action 分级
     * （同一工具的创建与删除分别定级），返回值应落在 [riskLevels] 的声明范围内。
     */
    fun riskLevel(args: JsonObject): RiskLevel

    /** 把 args 解析为领域 command；参数不合法抛异常。 */
    fun command(args: JsonObject): AttentionCommand

    /** 把执行结果投影为观测载荷；投影失败抛异常，与解析失败同为工具侧失败。 */
    fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject
}
