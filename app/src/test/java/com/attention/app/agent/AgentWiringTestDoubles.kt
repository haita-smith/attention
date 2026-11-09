package com.attention.app.agent

import com.attention.agent.AgentEvent
import com.attention.agent.AgentTool
import com.attention.agent.ChangeTool
import com.attention.agent.CommandExecutor
import com.attention.agent.Reasoner
import com.attention.agent.ReasonerDecision
import com.attention.agent.RiskLevel
import com.attention.app.data.audit.AgentTraceEntry
import com.attention.app.data.audit.AgentTraceStore
import com.attention.app.data.audit.AiSnapshot
import com.attention.app.data.audit.AiSnapshotStore
import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 接线测试共用的事实:固定派发时钟(ADR-0005,测试固定 now/zone)。
 * 与 `:agent` 的 ChangeTestDoubles 同口径,但替身全部自制——
 * app 测试不依赖 `:agent` 的 test 源集。
 */
val DISPATCH_NOW: Instant = Instant.parse("2026-03-06T02:00:00Z") // 上海时间 10:00
val DISPATCH_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 记录派发实况的执行器替身:command、now、zone 逐次留档;可选失败与派发时副作用。 */
class FakeDispatchExecutor(
    private val failure: Throwable? = null,
    private val onDispatch: () -> Unit = {},
) : CommandExecutor {
    val commands = mutableListOf<AttentionCommand>()
    val nows = mutableListOf<Instant>()
    val zones = mutableListOf<ZoneId>()

    val dispatchCount: Int get() = commands.size

    override suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult {
        commands += command
        nows += now
        zones += zone
        onDispatch()
        failure?.let { throw it }
        return AttentionCommandResult(AttentionState())
    }
}

/** 记录快照的存储替身;可注入失败,用来验证「快照写失败即变更失败」。 */
class RecordingSnapshotStore(
    private val failure: Throwable? = null,
) : AiSnapshotStore {
    val captured = mutableListOf<AttentionState>()

    override suspend fun capture(state: AttentionState) {
        captured += state
        failure?.let { throw it }
    }

    override suspend fun snapshots(): List<AiSnapshot> = emptyList()

    override suspend fun latest(): AiSnapshot? = null
}

/** 记录运行摘要的轨迹替身(仿 AiChangeAuditTest 的 RecordingTraceStore)。 */
class RecordingTraceStore : AgentTraceStore {
    val recorded = mutableListOf<AgentTraceEntry>()

    override val entries: Flow<List<AgentTraceEntry>> = flowOf(emptyList())

    override suspend fun record(entry: AgentTraceEntry) {
        recorded += entry
    }

    override suspend fun clear() = Unit
}

/** 按序吐出预设决策的脚本化推理器(仿 FakeReasoner);脚本耗尽即失败。 */
class ScriptedReasoner(vararg script: ReasonerDecision) : Reasoner {
    private val steps = script.toList()
    val histories = mutableListOf<List<AgentEvent>>()
    private var next = 0

    override suspend fun reason(request: String, history: List<AgentEvent>): ReasonerDecision {
        histories += history.toList()
        return steps.getOrElse(next++) { error("推理器脚本已耗尽(第 $next 次调用)") }
    }
}

/**
 * 脚本化变更工具:固定风险级别,逐次派发预设 command 队列——
 * 同一工具的多次调用可以映射不同 command,用来钉住
 * 「executor 是 command 名单的唯一准确观测点」。
 */
class ScriptedChangeTool(
    override val name: String = "scripted_change",
    override val riskLevels: Set<RiskLevel> = setOf(RiskLevel.Auto, RiskLevel.Confirmation),
    private val risk: RiskLevel = RiskLevel.Auto,
    private val commandQueue: ArrayDeque<AttentionCommand> =
        ArrayDeque(listOf(AttentionCommand.StopTimer)),
) : ChangeTool {
    override val description = "测试变更工具"
    override val parametersSchema = buildJsonObject { put("type", "object") }

    override fun riskLevel(args: JsonObject): RiskLevel = risk

    override fun command(args: JsonObject): AttentionCommand =
        commandQueue.removeFirstOrNull() ?: error("command 脚本已耗尽")

    override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject =
        buildJsonObject { put("ok", true) }
}

/** 固定载荷的只读工具替身:执行即记录调用并返回预设载荷(仿 RecordingTool)。 */
class ScriptedReadOnlyTool(
    override val name: String = "scripted_query",
    private val payload: JsonObject = buildJsonObject { put("count", 0) },
) : AgentTool {
    override val description = "测试只读工具"
    override val parametersSchema = buildJsonObject { put("type", "object") }

    val calls = mutableListOf<JsonObject>()

    override fun execute(args: JsonObject): JsonObject {
        calls += args
        return payload
    }
}
