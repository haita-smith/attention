package com.attention.agent

// JVM 上 kotlinx.coroutines.CancellationException 就是本文件导入的 JDK 类型的别名，
// 捕获它即可接住协程取消，不必为纯投影模块拉进 coroutines 依赖（与 ReActAgent 一致）。
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一个助手工具。[execute] 必须是状态的纯投影：同步、无副作用、可重复调用；
 * 它抛出的任何异常（参数不合法、状态缺失）都由循环转成错误观测回填给推理器，
 * 因此工具内部不需要也不应该自行把失败编码进返回值。
 * 变更类动作（提交领域 command）走平行的 [ChangeTool]，不从这里扩展。
 */
interface AgentTool {
    /** 注册名，循环按它查找；推理器通过它引用工具，注册后不可变。 */
    val name: String

    /** 一句话说明工具回答什么问题，下一阶段直接进入 prompt。 */
    val description: String

    /** 参数的 JSON Schema（简版 object schema），下一阶段 LLM 绑定直接可用。 */
    val parametersSchema: JsonObject

    fun execute(args: JsonObject): JsonObject
}

/** 异常给推理器看的是人话：有消息用消息，没有消息退到异常类名。 */
internal fun describeFailure(failure: Throwable): String =
    failure.message ?: failure::class.java.simpleName

/** 统一的错误观测形状：载荷只带一个 error 字段，isError 标记工具侧失败。 */
internal fun errorObservation(message: String): AgentEvent.Observation =
    AgentEvent.Observation(buildJsonObject { put("error", message) }, isError = true)

/**
 * 目录统一挂起执行的收敛结果。工具侧的一切失败已经在这里收敛成错误观测；
 * 只有必须终止循环的执行通道故障以值上行，由循环收敛为失败终态。
 */
sealed interface ToolExecution {
    /** 回填给推理器的观测：成功载荷或错误说明。 */
    data class Observed(val observation: AgentEvent.Observation) : ToolExecution

    /** 执行通道故障：循环以失败终态收敛，trace 保留到该次行动为止。 */
    data class DispatchFailed(val reason: String) : ToolExecution

    /** 确认级动作已登记待派发：循环不回填观测，以待确认终态短路，令牌交回调入口裁决。 */
    data class AwaitingConfirmation(val token: ConfirmationToken) : ToolExecution
}

/**
 * 工具注册表。重名在构造时立即失败：两个工具共用一个名字意味着推理器无法
 * 无歧义地引用它们，这种组合根错误不该等到运行中的某次调用才暴露。
 *
 * 目录同时接收只读（[AgentTool]）与变更（[ChangeTool]）两类工具，并把同步只读
 * 执行与挂起变更执行适配成统一的挂起 [execute]：循环对两类工具一视同仁，
 * 只读接口与循环的只读语义不因变更通道的存在而改变。
 *
 * [confirmationEnabled] 是确认通道的构造期降级开关：关闭时确认级动作整体不可达
 * （一律错误观测，与是否装配 [ConfirmationGate] 无关）——宁可不可用，不可绕过确认，
 * 绝不静默降级为自动执行；运行期不切换，需要时由组合根重建目录。开启时确认语义
 * 与 P0.2 交付的短路终态与确认回调完全一致。
 */
class ToolCatalog(
    tools: List<AgentTool> = emptyList(),
    changeTools: List<ChangeTool> = emptyList(),
    executor: CommandExecutor? = null,
    clock: ChangeClockSource? = null,
    gate: ConfirmationGate? = null,
    private val confirmationEnabled: Boolean = true,
) {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }
    private val changeByName: Map<String, ChangeTool> = changeTools.associateBy { it.name }
    private val dispatch: CommandExecutor? = executor
    private val clockSource: ChangeClockSource? = clock
    private val confirmationGate: ConfirmationGate? = gate

    val all: List<AgentTool> = tools.toList()
    val changeTools: List<ChangeTool> = changeTools.toList()

    init {
        require(byName.size == tools.size) {
            val duplicated = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
            "工具名重复：$duplicated"
        }
        require(changeByName.size == changeTools.size) {
            val duplicated = changeTools.groupBy { it.name }.filterValues { it.size > 1 }.keys
            "工具名重复：$duplicated"
        }
        // 只读与变更共用一个命名空间：推理器按名引用工具，跨类别重名同样无法无歧义引用
        val colliding = byName.keys.intersect(changeByName.keys)
        require(colliding.isEmpty()) { "工具名在只读与变更清单间重复：$colliding" }
        // Forbidden 声明与重名即败同一口径：永禁操作在构造期就挡在能力清单之外，
        // 不给任何运行期请求留触达它的可能
        val forbidden = changeTools.filter { RiskLevel.Forbidden in it.riskLevels }.map { it.name }
        require(forbidden.isEmpty()) { "工具声明了 Forbidden 级 action，禁止注册：$forbidden" }
        // 变更工具缺执行通道是组合根装配错误：等到运行中的某次调用才暴露就晚了
        require(changeTools.isEmpty() || (executor != null && clock != null)) {
            "存在变更工具但未提供执行通道（CommandExecutor 与 ChangeClockSource 必须同时注入）"
        }
    }

    /** 按名查找只读工具；未知名字返回 null，由调用方决定如何呈现给推理器。 */
    operator fun get(name: String): AgentTool? = byName[name]

    /**
     * 统一挂起执行：按名派发一次工具调用。工具侧的一切失败（未知工具、参数不合法、
     * 执行与投影异常）都收敛为错误观测；只有执行通道故障以
     * [ToolExecution.DispatchFailed] 值上行，由循环收敛为失败终态。
     */
    suspend fun execute(name: String, args: JsonObject): ToolExecution {
        changeByName[name]?.let { return executeChange(it, args) }
        val tool = byName[name] ?: return ToolExecution.Observed(errorObservation("未知工具：$name"))
        return ToolExecution.Observed(AgentEvent.Observation(tool.execute(args)))
    }

    /** 变更工具的三相编排：风险判定决定走向，只有 Auto 级在循环内即时派发。 */
    private suspend fun executeChange(tool: ChangeTool, args: JsonObject): ToolExecution {
        val risk = try {
            tool.riskLevel(args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ToolExecution.Observed(errorObservation(describeFailure(failure)))
        }
        return when (risk) {
            RiskLevel.Auto -> executeAuto(tool, args)
            RiskLevel.Confirmation -> executeConfirmation(tool, args)
            // Forbidden 声明在注册期已被拒绝；这里只防工具运行期越级谎报，保持循环不抛。
            RiskLevel.Forbidden -> ToolExecution.Observed(errorObservation("禁止执行的操作：${tool.name}"))
        }
    }

    /**
     * 确认级的登记相：解析出 command 交给确认回调入口持有，以待确认结果短路上行。
     * 确认通道关闭或缺失即该级动作整体不可达——宁可报错，不产生令牌、不派发、
     * 不静默转自动；解析失败是工具侧失败，与 Auto 级同口径收敛为错误观测，同样不产生令牌。
     */
    private suspend fun executeConfirmation(tool: ChangeTool, args: JsonObject): ToolExecution {
        val gate = confirmationGate
        if (!confirmationEnabled || gate == null) {
            return ToolExecution.Observed(errorObservation("需确认操作暂不可用"))
        }
        val command = try {
            tool.command(args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ToolExecution.Observed(errorObservation(describeFailure(failure)))
        }
        return ToolExecution.AwaitingConfirmation(gate.register(command))
    }

    /** Auto 级的派发相：解析与投影是工具侧失败（错误观测），executor 失败是通道故障（值上行）。 */
    private suspend fun executeAuto(tool: ChangeTool, args: JsonObject): ToolExecution {
        val command = try {
            tool.command(args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ToolExecution.Observed(errorObservation(describeFailure(failure)))
        }
        val moment = requireNotNull(clockSource) { "变更工具缺少时钟来源" }.snapshot()
        val result = try {
            requireNotNull(dispatch) { "变更工具缺少执行通道" }.execute(command, moment.now, moment.zone)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return ToolExecution.DispatchFailed(describeFailure(failure))
        }
        return try {
            ToolExecution.Observed(AgentEvent.Observation(tool.observation(args, result)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ToolExecution.Observed(errorObservation(describeFailure(failure)))
        }
    }
}
