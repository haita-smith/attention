package com.attention.agent

import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
 * 并发下恰好派发一次（先取得裁决权的调用方赢，其余一律返回已裁决）。
 *
 * 每枚令牌的全部状态收敛在单个裁决单元里，转移只经引用级 CAS 完成：confirm 取得
 * 裁决权（待裁决 → 在途，以在途实例的引用身份界定所有权）后结算——派发落地即终态；
 * 派发未落地则仅当单元仍持有自己的在途实例时回档为待裁决。并发的任何一方
 * （确认或取消）只要在在途窗口内到达，就把单元原子地消费为已裁决：回档比对失配、
 * 回档被拒绝，令牌绝不复活——已按「已裁决」回答过的令牌，事后不得再变得可裁决。
 */
class ConfirmationGate(
    private val executor: CommandExecutor,
    private val clock: ChangeClockSource,
) {
    /** 一笔待派发的确认：command 与短路时刻的时钟快照由 [ChangeClock] 成对冻结（ADR-0005），确认后原样派发。 */
    private data class PendingDispatch(val command: AttentionCommand, val moment: ChangeClock)

    /** 单枚令牌的裁决状态。状态对象只以引用比较参与 CAS，身份唯一，不存在等值混淆。 */
    private sealed interface State {
        /** 待裁决：持有冻结的派发项，等待某次 confirm 取走或某次 cancel 消费。 */
        class Awaiting(val dispatch: PendingDispatch) : State

        /**
         * 在途：裁决权已归属铸出本实例的那次 confirm（以本实例的引用身份界定），
         * command 正在执行通道上；只有持有本实例的 confirm 能把它带回待裁决。
         */
        class InFlight(val dispatch: PendingDispatch) : State

        /** 终态：已被一次性消费（确认落地、取消、或在途消费），此后一律已裁决。 */
        class Resolved : State
    }

    private val adjudications = ConcurrentHashMap<ConfirmationToken, AtomicReference<State>>()
    private val sequence = AtomicLong()

    /**
     * 登记一笔待确认派发：铸造新令牌并冻结 command 与当前时钟快照。
     * 令牌值取自单调序列，命名空间内唯一；只读通道不经过这里，无并发登记之外的入口。
     */
    internal fun register(command: AttentionCommand): ConfirmationToken {
        val token = ConfirmationToken(sequence.incrementAndGet().toString())
        adjudications[token] = AtomicReference<State>(State.Awaiting(PendingDispatch(command, clock.snapshot())))
        return token
    }

    /**
     * 确认：先原子地取得裁决权（待裁决 → 在途），再经同一 executor 派发一次。执行通道故障
     * 不是裁决——异常原样上行给调用方，并凭在途实例的引用身份回档为待裁决，这次确认机会
     * 不被吞掉（协程取消同口径：回档后原样穿透，绝不被吞成结果值）；单元已被并发方消费
     * 即裁决权易主，回档拒绝，令牌不复活。
     */
    suspend fun confirm(token: ConfirmationToken): ConfirmationResolution {
        val adjudication = adjudications[token] ?: return ConfirmationResolution.UnknownToken
        while (true) {
            when (val current = adjudication.get()) {
                is State.Awaiting -> {
                    val inFlight = State.InFlight(current.dispatch)
                    if (adjudication.compareAndSet(current, inFlight)) {
                        return adjudicate(adjudication, inFlight)
                    }
                    // CAS 失败即快照过期：不基于过期状态下结论，重读再判
                }
                is State.InFlight ->
                    if (adjudication.compareAndSet(current, State.Resolved())) {
                        return ConfirmationResolution.AlreadyResolved
                    }
                is State.Resolved -> return ConfirmationResolution.AlreadyResolved
            }
        }
    }

    /**
     * 取消：不执行任何 command，令牌同样一次性消费。在途窗口内到达的取消无法撤回
     * 可能已发生的派发，按已裁决回答并原子地消费单元——这让随后失败的回档无法复活令牌，
     * 已作出的「已裁决」回答不会被事后的重试推翻。
     */
    fun cancel(token: ConfirmationToken): ConfirmationResolution {
        val adjudication = adjudications[token] ?: return ConfirmationResolution.UnknownToken
        while (true) {
            when (val current = adjudication.get()) {
                is State.Awaiting ->
                    if (adjudication.compareAndSet(current, State.Resolved())) {
                        return ConfirmationResolution.Cancelled
                    }
                is State.InFlight ->
                    if (adjudication.compareAndSet(current, State.Resolved())) {
                        return ConfirmationResolution.AlreadyResolved
                    }
                is State.Resolved -> return ConfirmationResolution.AlreadyResolved
            }
        }
    }

    /** 裁决权在手：派发一次。落地即终态（并发消费不改变派发事实，仍以执行结果回答）；未落地凭在途实例回档。 */
    private suspend fun adjudicate(
        adjudication: AtomicReference<State>,
        inFlight: State.InFlight,
    ): ConfirmationResolution {
        val dispatch = inFlight.dispatch
        return try {
            val result = executor.execute(dispatch.command, dispatch.moment.now, dispatch.moment.zone)
            adjudication.compareAndSet(inFlight, State.Resolved())
            ConfirmationResolution.Executed(result)
        } catch (cancelled: CancellationException) {
            undoAdjudication(adjudication, inFlight)
            throw cancelled
        } catch (failure: Throwable) {
            undoAdjudication(adjudication, inFlight)
            throw failure
        }
    }

    /** 派发未落地就把裁决账冲回：仅当裁决权仍在本 confirm 手里才回到待裁决；并发方已消费则拒绝回档。 */
    private fun undoAdjudication(adjudication: AtomicReference<State>, inFlight: State.InFlight) {
        adjudication.compareAndSet(inFlight, State.Awaiting(inFlight.dispatch))
    }
}
