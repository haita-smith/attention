package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 变更执行通道测试共用的事实:固定派发时钟(ADR-0005,测试固定 now/zone)
 * 与一个可整体断言相等的执行结果。
 */
val DISPATCH_NOW: Instant = Instant.parse("2026-03-06T02:00:00Z") // 上海时间 10:00
val DISPATCH_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
val DISPATCHED_RESULT: AttentionCommandResult = AttentionCommandResult(AttentionState())

/** 记录派发实况的执行器(仿 FakeReasoner 的记录风格):command、now、zone 逐次留档,返回预设结果。 */
class FakeCommandExecutor(
    private val result: AttentionCommandResult = DISPATCHED_RESULT,
    private val failure: Throwable? = null,
) : CommandExecutor {
    val commands = mutableListOf<AttentionCommand>()
    val nows = mutableListOf<Instant>()
    val zones = mutableListOf<ZoneId>()

    val dispatchCount: Int get() = commands.size

    override suspend fun execute(command: AttentionCommand, now: Instant, zone: ZoneId): AttentionCommandResult {
        commands += command
        nows += now
        zones += zone
        failure?.let { throw it }
        return result
    }
}

/**
 * 脚本化变更工具(仿 RecordingTool):固定风险级别、command 与观测载荷,
 * 记录解析入参、渲染到的执行结果,供测试断言三段各自拿到了什么。
 */
class ScriptedChangeTool(
    override val name: String = "stop_timer",
    override val riskLevels: Set<RiskLevel> = setOf(RiskLevel.Auto),
    private val risk: RiskLevel = RiskLevel.Auto,
    private val command: AttentionCommand = AttentionCommand.StopTimer,
    private val payload: JsonObject = buildJsonObject { put("stopped", true) },
) : ChangeTool {
    override val description = "测试变更工具"
    override val parametersSchema = buildJsonObject { put("type", "object") }

    val parsedArgs = mutableListOf<JsonObject>()
    val renderedResults = mutableListOf<AttentionCommandResult>()

    override fun riskLevel(args: JsonObject): RiskLevel = risk

    override fun command(args: JsonObject): AttentionCommand {
        parsedArgs += args
        return command
    }

    override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject {
        renderedResults += result
        return payload
    }
}

/** 变更工具在哪一相失败:风险判定、command 解析、观测投影。 */
enum class ChangeToolPhase { RISK, COMMAND, RENDER }

/** 一被调用就在指定相失败的工具(仿 ThrowingTool),用来验证循环把工具异常转成错误观测。 */
class ThrowingChangeTool(
    private val failure: Throwable = IllegalStateException("变更工具炸了"),
    private val phase: ChangeToolPhase = ChangeToolPhase.COMMAND,
    override val name: String = "boom_change",
    override val riskLevels: Set<RiskLevel> = setOf(RiskLevel.Auto),
) : ChangeTool {
    override val description = "测试变更工具"
    override val parametersSchema = buildJsonObject { put("type", "object") }

    override fun riskLevel(args: JsonObject): RiskLevel {
        if (phase == ChangeToolPhase.RISK) throw failure
        return RiskLevel.Auto
    }

    override fun command(args: JsonObject): AttentionCommand {
        if (phase == ChangeToolPhase.COMMAND) throw failure
        return AttentionCommand.StopTimer
    }

    override fun observation(args: JsonObject, result: AttentionCommandResult): JsonObject {
        if (phase == ChangeToolPhase.RENDER) throw failure
        return buildJsonObject { put("stopped", true) }
    }
}
