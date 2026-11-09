package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

/**
 * 确认令牌：一次待确认动作的裁决凭据。值由 [ConfirmationGate] 铸造，在 gate
 * 命名空间内唯一、一次性消费；它随待确认事件与终态走出循环，经确认回调入口原样带回。
 */
@Serializable
data class ConfirmationToken(val value: String)

/**
 * 一次裁决的结果值。确认回调入口对一切路径都以值回答——未知令牌、已消费令牌
 * 都不是异常，调用方不需要 try/catch；只有执行通道自身的故障（见 [ConfirmationGate]）
 * 会以异常上行，那不是裁决。
 */
sealed interface ConfirmationResolution {
    /** 用户确认且 command 已经派发：携带执行结果。 */
    data class Executed(val result: AttentionCommandResult) : ConfirmationResolution

    /** 用户取消：command 未派发，数据保持原样。 */
    data object Cancelled : ConfirmationResolution

    /** 令牌从未在本 gate 登记过。 */
    data object UnknownToken : ConfirmationResolution

    /** 令牌已被裁决（确认或取消）并一次性消费。 */
    data object AlreadyResolved : ConfirmationResolution
}

/**
 * 确认回调入口：由组合根创建并持有，生命周期覆盖「产生待确认 → 用户裁决」全程。
 * 短路时目录把待派发的 command 与短路时刻的时钟快照登记在这里（[register]）；
 * 用户的裁决经 confirm / cancel 异步到达——确认才经构造时注入的同一 [CommandExecutor]
 * 派发被持有的 command，取消不执行。令牌一次性消费：确认与取消都是裁决，
 * 并发下恰好派发一次（先取走待派发项的裁决者赢，其余一律返回已裁决）。
 */
class ConfirmationGate(
    private val executor: CommandExecutor,
    private val clock: ChangeClockSource,
) {
    /** 一笔待派发的确认：command 与短路时刻的 now/zone 成对冻结（ADR-0005），确认后原样派发。 */
    private data class PendingDispatch(val command: AttentionCommand, val now: Instant, val zone: ZoneId)

    private val pending = ConcurrentHashMap<ConfirmationToken, PendingDispatch>()
    private val resolved = ConcurrentHashMap.newKeySet<ConfirmationToken>()
    private val sequence = AtomicLong()

    /**
     * 登记一笔待确认派发：铸造新令牌并冻结 command 与当前时钟快照。
     * 令牌值取自单调序列，命名空间内唯一；只读通道不经过这里，无并发登记之外的入口。
     */
    internal fun register(command: AttentionCommand): ConfirmationToken {
        val moment = clock.snapshot()
        val token = ConfirmationToken(sequence.incrementAndGet().toString())
        pending[token] = PendingDispatch(command, moment.now, moment.zone)
        return token
    }

    /**
     * 确认：取走待派发项并经同一 executor 派发一次。执行通道故障不是裁决——
     * 异常原样上行给调用方，令牌回到待裁决，这次确认机会不被吞掉
     * （协程取消同口径：回档后原样穿透，绝不被吞成结果值）。
     */
    suspend fun confirm(token: ConfirmationToken): ConfirmationResolution {
        val dispatch = pending.remove(token) ?: return resolutionFor(token)
        resolved += token
        return try {
            ConfirmationResolution.Executed(executor.execute(dispatch.command, dispatch.now, dispatch.zone))
        } catch (cancelled: CancellationException) {
            undoResolution(token, dispatch)
            throw cancelled
        } catch (failure: Throwable) {
            undoResolution(token, dispatch)
            throw failure
        }
    }

    /** 取消：不执行任何 command,令牌同样一次性消费。 */
    fun cancel(token: ConfirmationToken): ConfirmationResolution {
        if (pending.remove(token) == null) return resolutionFor(token)
        resolved += token
        return ConfirmationResolution.Cancelled
    }

    /** 已登记但未裁决 → 已裁决;从未登记 → 未知。取走失败后按已裁决集区分二者。 */
    private fun resolutionFor(token: ConfirmationToken): ConfirmationResolution =
        if (token in resolved) ConfirmationResolution.AlreadyResolved else ConfirmationResolution.UnknownToken

    /** 派发未落地就把裁决账冲回:令牌回到待裁决,已裁决集撤销本次占用。 */
    private fun undoResolution(token: ConfirmationToken, dispatch: PendingDispatch) {
        if (resolved.remove(token)) {
            pending[token] = dispatch
        }
    }
}
