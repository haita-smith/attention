package com.attention.app.agent

import com.attention.agent.AgentOutcome
import com.attention.agent.AgentTool
import com.attention.agent.ChangeClock
import com.attention.agent.ChangeClockSource
import com.attention.agent.ChangeTool
import com.attention.agent.CommandExecutor
import com.attention.agent.ConfirmationToken
import com.attention.agent.ConfirmationResolution
import com.attention.agent.ConfirmationGate
import com.attention.agent.ManageTargetTool
import com.attention.agent.Reasoner
import com.attention.agent.RecordTimeTool
import com.attention.agent.ReActAgent
import com.attention.agent.SetGoalTool
import com.attention.agent.TimerControlTool
import com.attention.agent.ToolCatalog
import com.attention.agent.WorkspaceSource
import com.attention.agent.WorkspaceTools
import com.attention.agent.WorkspaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.attention.app.AttentionViewModel
import com.attention.app.data.AttentionStateRepository
import com.attention.app.data.audit.AiChangeAudit
import com.attention.app.data.audit.createAiChangeAudit
import com.attention.domain.AttentionState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 推理模型未接入(#230)前的构造期占位:一次调用即失败,与
 * 「宁可不可用,不可静默降级」同一姿态。组合根以它完成装配;
 * #230 落地后把构造处替换为真实推理器,接线不再改动。
 * 失败经 ReAct 循环收敛为 Failed 终态,运行入口不抛异常。
 */
private object ReasonerNotBound : Reasoner {
    override suspend fun reason(
        request: String,
        history: List<com.attention.agent.AgentEvent>,
    ): com.attention.agent.ReasonerDecision = error("推理模型尚未接入(#230),助手运行入口不可用")
}

/**
 * 组合根持有的助手运行时(ADR-0009):仓库派发通道、审计门面、只读与变更工具、
 * 确认门与 ReAct 循环在此一处装配,对外只暴露三个成员——
 * 挂起 [run](运行边界)、确认门裁决转发 [confirm]/[cancel](供未来的确认卡片)。
 *
 * 不变式:
 * - 一切 AI 驱动的变更派发(自动级、确认级)都经过审计包装的执行通道,
 *   快照写入点因此只有一处;手动路径不经本类,行为与接线前一致。
 * - 时钟与状态都是动态来源:每次工具投影、派发与确认裁决各取现值,
 *   与 ADR-0005 的组合根落点一致;不复用组合根单次捕获的 now/zone,
 *   因为确认门的裁决可能远晚于装配。
 * - [run] 以互斥串行化:一次只有一个运行边界,边界内的 command 名单
 *   在运行结束映射结局后写入审计摘要。摘要写失败原样上行——
 *   「每次运行都有摘要可查」是写入点承诺,不静默丢摘要。
 * - 降级开关 [confirmationEnabled] 首版关闭(构造期常量):确认级动作整体不可用,
 *   以错误观测回填;对话页接入确认卡片时由组合根翻真。待确认终态不写运行摘要,
 *   其审计口径随确认线一起扩展。
 *
 * [readOnlyTools]/[changeTools] 传 null 即生产装配(只读四件 + 变更四件套,
 * 全部只依赖注入的 [WorkspaceSource]);测试注入脚本化替身,公开面不变。
 * [reasoner] 是构造参数(#230 注入),本票不实现推理模型。
 */
class WorkspaceAgentRuntime(
    dispatch: CommandExecutor,
    private val audit: AiChangeAudit,
    state: () -> AttentionState,
    now: () -> Instant = Instant::now,
    zone: () -> ZoneId = ZoneId::systemDefault,
    reasoner: Reasoner = ReasonerNotBound,
    readOnlyTools: List<AgentTool>? = null,
    changeTools: List<ChangeTool>? = null,
    private val confirmationEnabled: Boolean = false,
    maxSteps: Int = ReActAgent.DEFAULT_MAX_STEPS,
) {

    /** 工具看到的快照:领域状态加显式成对注入的 now 与 zone(ADR-0005)。 */
    private val source = WorkspaceSource { WorkspaceView(state(), now(), zone()) }

    /** 审计与快照的写入点:一切 AI 变更派发的唯一通道。 */
    private val executor = AuditedCommandExecutor(dispatch, audit, state)

    /** 动态时钟来源:确认门与自动级派发共用同一套时钟约定。 */
    private val clockSource = ChangeClockSource { ChangeClock(now(), zone()) }

    /** 确认回调入口:生命周期由组合根长期持有,覆盖「产生待确认 → 用户裁决」全程。 */
    private val gate = ConfirmationGate(executor, clockSource)

    private val catalog = ToolCatalog(
        tools = readOnlyTools ?: WorkspaceTools(source).all(),
        changeTools = changeTools
            ?: listOf(ManageTargetTool(source), SetGoalTool(source), RecordTimeTool(source), TimerControlTool(source)),
        executor = executor,
        clock = clockSource,
        gate = gate,
        confirmationEnabled = confirmationEnabled,
    )

    private val agent = ReActAgent(reasoner, catalog, maxSteps)

    /** 串行化运行边界:一次只有一个 run 在收集 command 名单。 */
    private val runMutex = Mutex()

    /**
     * 跑一次助手请求:请求进,领域终态出。运行结束把边界内收集的领域 command
     * 简单类名与映射后的结局写入审计摘要;待确认终态短路收敛、不经映射,
     * 摘要随确认线的扩展再补(裁决可能让同一令牌最终派发,口径不能提前定)。
     *
     * 收账只有唯一出口:边界关闭与摘要写入都在出口处一次性结算,名单只取一次,
     * 正确性不依赖重复收尾的幂等。[agent.run] 仅在取消时抛出,此时终态缺席、
     * 只关边界不记账。
     */
    suspend fun run(request: String): AgentOutcome = runMutex.withLock {
        executor.beginRun()
        var finished: AgentOutcome? = null
        try {
            agent.run(request).also { finished = it }
        } finally {
            val outcome = finished
            val commands = executor.finishRun()
            if (outcome != null && outcome !is AgentOutcome.PendingConfirmation) {
                audit.recordRun(request, outcome.toAgentRunOutcome(), commands)
            }
        }
    }

    /** 确认门裁决转发:用户同意则派发被持有的 command(同样先快照)。 */
    suspend fun confirm(token: ConfirmationToken): ConfirmationResolution = gate.confirm(token)

    /** 确认门裁决转发:取消则令牌一次性消费,command 不派发。 */
    fun cancel(token: ConfirmationToken): ConfirmationResolution = gate.cancel(token)
}

/**
 * 组合根装配(WorkspaceApp 内 remember 手写构造,对齐既有风格,不引入 DI):
 * 派发通道一行委托仓库的 executeCommand,审计门面走既有工厂,
 * WorkspaceSource 读 ViewModel 已暴露的最新状态(StateFlow.value 形态)。
 *
 * 「状态加载完成前不得运行助手」的约束记录在案,由对话线负责把关——
 * 本票不接任何 UI 触发入口,run 在生产装配下不可达;#230 注入真实 Reasoner、
 * 对话页接线后,这里不需要再改动。
 */
@Composable
internal fun rememberWorkspaceAgentRuntime(
    repository: AttentionStateRepository,
    viewModel: AttentionViewModel,
): WorkspaceAgentRuntime {
    val context = LocalContext.current
    return remember(repository) {
        WorkspaceAgentRuntime(
            dispatch = CommandExecutor { command, now, zone -> repository.executeCommand(command, now, zone) },
            audit = context.createAiChangeAudit(),
            state = { viewModel.state.value },
        )
    }
}
