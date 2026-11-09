package com.attention.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 保存日程条目时顺带补记的一段已经发生的时间。
 *
 * [targetId] 是用户选择归属的计划；null 表示这笔补记先保持为未归属活动，之后可以再归入计划。
 *
 * [requestId] 是这一次**提交意图**的标识，也是防重复的唯一粒度：同一次保存被重试、
 * 被双击或被事务重放时沿用同一个值，重放只记一笔；下一次新的补记是新的值，必然被记录。
 * 取值规则由调用方掌握——页面把草稿组装成 [ScheduleBackfill] 一次并复用该实例即可，
 * 重试与重放复用同一个 [requestId]，用户再次主动补填时重新组装得到新的 [requestId]。
 */
data class ScheduleBackfill(
    val durationMinutes: Int,
    val targetId: String? = null,
    val note: String = "",
    val requestId: String = newId(),
    /** 编辑已有日程时按稳定的 scheduleEntryId 去重；新建日程保留多次显式补记语义。 */
    val dedupeExistingSchedule: Boolean = false,
)

/**
 * Typed seam for domain intents that change actual investment.
 *
 * A command is pure with respect to its inputs: callers provide the current state,
 * execution time, and zone explicitly, and receive an immutable result containing
 * the next state plus facts produced by the command.
 */
sealed interface AttentionCommand {
    fun execute(
        state: AttentionState,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AttentionCommandResult

    /**
     * 保存一条日程条目，并可选地顺带补记一笔事后补记来源的实际投入。
     *
     * 两件事在同一个 command 结果里完成：日程条目与那笔补记要么一起落库，要么都不落库，
     * 不存在只有一半的中间状态。补记记录的来源是 [TimeEntrySource.MANUAL]，并带上日程关联；
     * 它与 [TimeEntrySource.SCHEDULE_CONFIRMED]、[TimeEntrySource.TIMER] 三者不混。
     *
     * 防重复的粒度是**提交意图**而不是这条日程：同一次保存（重试、双击、事务重放）带着同一个
     * [ScheduleBackfill.requestId] 时只记一笔；用户下一次显式补填是新的事实，即使这条日程已经
     * 计时过或已被确认完成，也照样被记录下来。
     */
    data class SaveSchedule(
        val entry: ScheduleEntry,
        val backfill: ScheduleBackfill? = null,
    ) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            val existingSchedule = state.schedules.any { it.id == entry.id }
            val saved = if (existingSchedule) state.updateSchedule(entry) else state.addSchedule(entry)
            // 编辑已有日程时，稳定的 scheduleEntryId 是补记的去重边界：
            // 重复保存同一条日程不能再创建第二笔 MANUAL 记录。新建日程仍由 requestId
            // 保证同一次提交重放幂等。
            if (
                backfill == null ||
                state.appliedBackfill(backfill.requestId) != null ||
                (backfill.dedupeExistingSchedule && existingSchedule && state.backfillsForSchedule(entry.id).isNotEmpty())
            ) {
                return saved.withMilestonesFor(emptyList(), now.toEpochMilli())
            }
            val recorded = saved.addTimeEntry(
                planningDate = entry.planningDate,
                durationMinutes = backfill.durationMinutes,
                targetId = backfill.targetId,
                source = TimeEntrySource.MANUAL,
                note = backfill.note,
                scheduleEntryId = entry.id,
                backfillRequestId = backfill.requestId,
            )
            // 补记同样会把实际投入推上去，所以保存之后也要结算完成状态：否则用户用补记把投入
            // 凑满计划时长，日程仍停在未完成，直到别处碰一次别的命令才对上。
            return recorded.completeScheduleMetByInvestment(entry.id).withMilestonesFor(
                affectedPlanningDates = setOf(LocalDate.parse(entry.planningDate)),
                achievedAtEpochMillis = now.toEpochMilli(),
                generatedTimeEntries = listOf(recorded.timeEntries.last()),
            )
        }
    }

    /** Adds one manually entered actual-investment record. */
    data class AddManualTimeEntry(
        val planningDate: String,
        val durationMinutes: Int,
        val targetId: String? = null,
        val note: String = "",
    ) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            val nextState = state.addTimeEntry(
                planningDate = planningDate,
                durationMinutes = durationMinutes,
                targetId = targetId,
                source = TimeEntrySource.MANUAL,
                note = note,
            )
            val entry = nextState.timeEntries.last()
            return nextState.withMilestonesFor(
                affectedPlanningDates = setOf(LocalDate.parse(entry.planningDate)),
                achievedAtEpochMillis = now.toEpochMilli(),
                generatedTimeEntries = listOf(entry),
            )
        }
    }

    /**
     * Confirms one planned entry as actual invested time.
     *
     * 幂等的粒度是**这个动作**：同一条日程重复确认仍是同一个动作，因此已存在关联该日程的
     * [TimeEntrySource.SCHEDULE_CONFIRMED] 记录时只保证完成标记，不产生第二笔，投入经验与
     * 达成里程碑都不重复发放。已存在的事后补记或计时结算是各自独立的事实，不阻止确认记账。
     */
    data class ConfirmSchedule(val entryId: String, val durationMinutes: Int? = null) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            if (state.hasConfirmedInvestmentFor(entryId)) {
                return AttentionCommandResult(state.confirmScheduleCompleted(entryId, durationMinutes))
            }
            val nextState = state.confirmScheduleCompleted(entryId, durationMinutes)
            val entry = nextState.timeEntries.last()
            return nextState.withMilestonesFor(
                affectedPlanningDates = setOf(LocalDate.parse(entry.planningDate)),
                achievedAtEpochMillis = now.toEpochMilli(),
                generatedTimeEntries = listOf(entry),
            )
        }
    }

    /**
     * Decides one schedule entry's completion from its planned duration and the actual
     * investment linked to it.
     *
     * Reached the plan: the entry becomes completed. Short of the plan, or with no planned
     * duration at all, the entry keeps its state and the decision stays the user's. Running it
     * again settles nothing twice, because the completion only reads investment that already
     * exists and milestones are deduplicated by instance key.
     */
    data class SettleScheduleCompletion(val entryId: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            require(state.schedules.any { it.id == entryId }) { "日程条目不存在" }
            return state.completeScheduleMetByInvestment(entryId).withMilestonesFor(
                affectedPlanningDates = state.timeEntries
                    .filter { it.scheduleEntryId == entryId }
                    .map { LocalDate.parse(it.planningDate) },
                achievedAtEpochMillis = now.toEpochMilli(),
            )
        }
    }

    /**
     * Records the user's own decision that one schedule entry is done.
     *
     * [SettleScheduleCompletion] answers "has the investment reached the plan?"; this answers
     * "did the user decide it is done?". Running it on an entry that is already completed
     * changes nothing, and it never writes a second time entry — the stopped timer already
     * recorded that investment, so counting it again would double it.
     */
    data class MarkScheduleCompleted(val entryId: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.markScheduleCompleted(entryId))
    }

    /** Settles the active timer and evaluates every planning day it touched. */
    data object StopTimer : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            val active = state.activeTimer ?: return AttentionCommandResult(state)
            val stopped = state.stopTimer(now, zone)
            val settled = stopped.state.completeScheduleMetByInvestment(active.scheduleEntryId)
            return settled.withMilestonesFor(
                affectedPlanningDates = stopped.entries.map { LocalDate.parse(it.planningDate) },
                achievedAtEpochMillis = now.toEpochMilli(),
                generatedTimeEntries = stopped.entries,
            )
        }
    }

    /** Settles the previous timer, then starts a timer for a different target. */
    data class SwitchTimer(val targetId: String?, val scheduleEntryId: String? = null) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            require(state.validRecordTargetForCommand(targetId)) { "计时只能归入未归档目标" }
            val active = state.activeTimer
            if (active == null) return AttentionCommandResult(state.startTimer(targetId, now, zone, scheduleEntryId))
            if (active.targetId == targetId) return AttentionCommandResult(state)

            val stopped = state.stopTimer(now, zone)
            val settled = stopped.state.completeScheduleMetByInvestment(active.scheduleEntryId).withMilestonesFor(
                affectedPlanningDates = stopped.entries.map { LocalDate.parse(it.planningDate) },
                achievedAtEpochMillis = now.toEpochMilli(),
                generatedTimeEntries = stopped.entries,
            )
            return settled.copy(state = settled.state.startTimer(targetId, now, zone, scheduleEntryId))
        }
    }

    /** Edits only mutable ledger fields and preserves the record's recorded facts. */
    data class EditTimeEntry(
        val entryId: String,
        val durationMinutes: Int,
        val targetId: String?,
        val note: String = "",
    ) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            val existing = state.timeEntries.firstOrNull { it.id == entryId }
                ?: throw IllegalArgumentException("时间记录不存在")
            val nextState = state.editTimeEntry(entryId, durationMinutes, targetId, note)
            val edited = nextState.timeEntries.first { it.id == entryId }
            return nextState.completeScheduleMetByInvestment(existing.scheduleEntryId).withMilestonesFor(
                affectedPlanningDates = setOf(LocalDate.parse(existing.planningDate)),
                achievedAtEpochMillis = now.toEpochMilli(),
                generatedTimeEntries = listOf(edited),
            )
        }
    }

    /** Assigns a validated, all-or-nothing set of unowned records to one target. */
    data class AssignUnowned(val entryIds: Set<String>, val targetId: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            if (entryIds.isEmpty()) return AttentionCommandResult(state)
            val selected = state.timeEntries.filter { it.id in entryIds }
            val affectedDates = selected.map { LocalDate.parse(it.planningDate) }.toSet()
            val nextState = state.assignUnowned(entryIds, targetId)
            val assigned = nextState.timeEntries.filter { it.id in entryIds }
            return nextState.withMilestonesFor(
                affectedPlanningDates = affectedDates,
                achievedAtEpochMillis = now.toEpochMilli(),
                generatedTimeEntries = assigned,
            )
        }
    }

    /** Removes one ledger record and leaves the milestone history untouched. */
    data class DeleteTimeEntry(val entryId: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            val removed = state.timeEntries.firstOrNull { it.id == entryId }
            return AttentionCommandResult(
                state = state.deleteTimeEntry(entryId),
                removedTimeEntries = removed?.let(::listOf).orEmpty(),
            )
        }
    }

    /** Restores a previously deleted record without rewriting any of its recorded facts. */
    data class RestoreTimeEntry(val entry: TimeEntry) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            require(entry.durationMinutes > 0) { "时间记录时长必须为正数" }
            if (state.timeEntries.any { it.id == entry.id }) return AttentionCommandResult(state.recalculateExperience())
            val restored = state.copy(timeEntries = state.timeEntries + entry).recalculateExperience()
            return AttentionCommandResult(restored, timeEntries = listOf(entry))
        }
    }

    // ---- 目标树管理 command(P1.1 #241)----
    //
    // 本块全部是对既有引擎纯函数的薄包装:规则只活在 AttentionEngine 一处,command
    // 不复制校验、不改写结果。目标动作不触碰时间记录与里程碑,返回值只带 state;
    // 未来移动的生效界线一律取执行时刻的规划日(planningDate(now, zone)),与工具
    // 快照、周期结算对「今天」的理解同源。

    /** 在指定父目标(缺省为根)之下新建一个目标,排在同层末尾。 */
    data class CreateTarget(val title: String, val parentId: String? = null) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.addTarget(title, parentId))
    }

    /** 重命名一个已有目标;名称必须非空白,目标必须存在。 */
    data class RenameTarget(val targetId: String, val title: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.renameTarget(targetId, title))
    }

    /** 把目标在同层内重排到 [newIndex](0 起,越界拒绝)。 */
    data class ReorderTarget(val targetId: String, val newIndex: Int) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.reorderTarget(targetId, newIndex))
    }

    /** 展开或折叠一个目标分支;排序与展开折叠是持久化的领域状态,不是纯视图偏好。 */
    data class SetTargetExpanded(val targetId: String, val expanded: Boolean) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.setTargetExpanded(targetId, expanded))
    }

    /** 立即换父;防移入自身或后代,自动排到新父末尾(规则在 [AttentionState.moveTarget])。 */
    data class MoveTarget(val targetId: String, val newParentId: String?) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.moveTarget(targetId, newParentId))
    }

    /** 安排一次未来生效的换父;生效日必须晚于执行时刻的规划日。 */
    data class ScheduleTargetMove(val targetId: String, val parentId: String?, val effectiveFrom: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.addFutureTargetMove(targetId, parentId, effectiveFrom, state.planningDate(now, zone)))
    }

    /**
     * 修改一笔尚未生效的未来移动的父目标与生效日;移动的 id 与所属目标不可改。
     * 先按 [moveId] 取出既有移动、保持身份字段原样,再交既有领域函数按唯一性与
     * 生效界线校验更新——查找失败与校验失败都原样上行,不在此翻译或吞并。
     */
    data class UpdateTargetMove(val moveId: String, val parentId: String?, val effectiveFrom: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult {
            val existing = state.targetMoves.firstOrNull { it.id == moveId } ?: error("未来目标移动不存在")
            val updated = existing.copy(parentId = parentId, effectiveFrom = effectiveFrom)
            return AttentionCommandResult(state.updateFutureTargetMove(updated, state.planningDate(now, zone)))
        }
    }

    /** 取消一笔尚未生效的未来移动;已生效的历史移动不可取消,也不可改写。 */
    data class CancelTargetMove(val moveId: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.cancelFutureTargetMove(moveId, state.planningDate(now, zone)))
    }

    /** 归档一个目标;指向它的活动计时器置为未归属,时间记录保持原样。 */
    data class ArchiveTarget(val targetId: String) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.archiveTarget(targetId))
    }

    /** 恢复若干归档目标,并级联恢复其归档祖先,层级与排序原样保留。 */
    data class RestoreTargets(val targetIds: Set<String>) : AttentionCommand {
        override fun execute(state: AttentionState, now: Instant, zone: ZoneId): AttentionCommandResult =
            AttentionCommandResult(state.restoreTargets(targetIds))
    }
}

/** Result returned by every [AttentionCommand]. */
data class AttentionCommandResult(
    val state: AttentionState,
    val timeEntries: List<TimeEntry> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val removedTimeEntries: List<TimeEntry> = emptyList(),
) {
    val generatedTimeEntries: List<TimeEntry> get() = timeEntries
    val newMilestones: List<Milestone> get() = milestones
    val newState: AttentionState get() = state
}

fun AttentionState.execute(command: AttentionCommand, now: Instant, zone: ZoneId = ZoneId.systemDefault()): AttentionCommandResult =
    command.execute(this, now, zone)

fun AttentionCommand.run(state: AttentionState, now: Instant, zone: ZoneId = ZoneId.systemDefault()): AttentionCommandResult =
    execute(state, now, zone)

/** Applies the one milestone policy shared by all actual-investment commands. */
internal fun AttentionState.withMilestonesFor(
    affectedPlanningDates: Collection<LocalDate>,
    achievedAtEpochMillis: Long,
    generatedTimeEntries: List<TimeEntry> = emptyList(),
): AttentionCommandResult {
    val existingKeys = milestones.map { it.instanceKey }.toMutableSet()
    val candidates = affectedPlanningDates
        .distinct()
        .sorted()
        .flatMap { date -> eligibleMilestonesAt(date, achievedAtEpochMillis) }
        .filter { existingKeys.add(it.instanceKey) }
        .distinctBy { it.instanceKey }
        .sortedWith(compareBy<Milestone> { it.kind }.thenBy { it.instanceKey })
    val nextState = copy(milestones = milestones + candidates).recalculateExperience()
    return AttentionCommandResult(nextState, generatedTimeEntries.toList(), candidates.toList())
}

private fun AttentionState.validRecordTargetForCommand(targetId: String?): Boolean =
    targetId == null || targets.any { it.id == targetId && !it.archived }

typealias AddManualTimeEntryCommand = AttentionCommand.AddManualTimeEntry
typealias ManualTimeEntryCommand = AttentionCommand.AddManualTimeEntry
typealias SaveScheduleCommand = AttentionCommand.SaveSchedule
typealias ConfirmScheduleCommand = AttentionCommand.ConfirmSchedule
typealias ConfirmScheduleCompletedCommand = AttentionCommand.ConfirmSchedule
typealias EditTimeEntryCommand = AttentionCommand.EditTimeEntry
typealias AssignUnownedCommand = AttentionCommand.AssignUnowned
typealias AssignUnownedTimeEntriesCommand = AttentionCommand.AssignUnowned
typealias DeleteTimeEntryCommand = AttentionCommand.DeleteTimeEntry
typealias RestoreTimeEntryCommand = AttentionCommand.RestoreTimeEntry
