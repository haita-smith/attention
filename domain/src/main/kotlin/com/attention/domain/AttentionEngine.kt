package com.attention.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun AttentionState.planningDate(
    instant: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate {
    val local = instant.atZone(zone)
    return local.toLocalDate().let {
        if (local.toLocalTime().toSecondOfDay() / 60 < settings.planningDayBoundaryMinutes) it.minusDays(1) else it
    }
}

/**
 * Returns the agenda entries for a planning day, or every entry when no day is selected.
 * Entries are ordered by planning day, start time, end time, and stable id so that the
 * all-dates view does not change order when Room emits the same rows again.
 */
fun AttentionState.agendaEntries(planningDate: LocalDate? = null): List<ScheduleEntry> = schedules
    .asSequence()
    .filter { planningDate == null || it.planningDate == planningDate.toString() }
    .sortedWith(
        compareBy<ScheduleEntry> { it.planningDate }
            .thenBy { it.startMinute ?: Int.MAX_VALUE }
            .thenBy { it.endMinute ?: Int.MAX_VALUE }
            .thenBy { it.id },
    )
    .toList()

/**
 * 这条日程条目有没有具体开始时间。
 *
 * 有开始时间的日程落在时间轴上按开始/结束时间排列；没有的日程仍然属于这一天，
 * 但它没有时刻可排，因此进入独立的「未安排时间」区域，而不是被排到时间轴末尾假装有位置。
 */
fun ScheduleEntry.hasStartTime(): Boolean = startMinute != null

/**
 * 一个规划日上的时间轴分组。
 *
 * [timed] 是有具体开始时间的日程，按开始时间、结束时间与稳定 id 排列，正在计时的那条固定在最前；
 * [unscheduled] 是这一天里没有具体时间的日程。
 */
data class AgendaTimeline(
    val timed: List<ScheduleEntry>,
    val unscheduled: List<ScheduleEntry>,
)

/** 所有日期视图下的一个规划日分组，它自己带这一天的时间轴。 */
data class AgendaDayTimeline(
    val planningDate: String,
    val timeline: AgendaTimeline,
)

/** 正在进行计时的日程条目 id。自由计时没有日程来源，因此为 null，没有条目可以置顶。 */
fun AttentionState.timingScheduleEntryId(): String? = activeTimer?.scheduleEntryId

/**
 * 一个规划日的时间轴。正在计时的那条固定在顶部，其余按开始/结束时间排列；
 * 没有具体开始时间的日程进入 [AgendaTimeline.unscheduled]。
 *
 * 置顶跨过「有没有开始时间」这个划分：「未安排时间」里的日程同样可以起计时，
 * 它一旦正在计时就出现在时间轴第一位，而不是留在未安排时间区域里。
 * 没有日程来源的自由计时没有条目可置顶，时间轴保持原样。
 */
fun AttentionState.agendaTimeline(planningDate: LocalDate): AgendaTimeline {
    val entries = agendaEntries(planningDate)
    val runningId = timingScheduleEntryId()
    fun ScheduleEntry.onTimeline() = hasStartTime() || id == runningId
    val timed = entries
        .filter { it.onTimeline() }
        .sortedWith(
            // 置顶先于时间排序：正在计时的条目即使时段在后面，也必须是第一行
            compareByDescending<ScheduleEntry> { it.id == runningId }
                .thenBy { it.startMinute }
                .thenBy { it.endMinute ?: Int.MAX_VALUE }
                .thenBy { it.id },
        )
    return AgendaTimeline(timed = timed, unscheduled = entries.filterNot { it.onTimeline() })
}

/**
 * 所有日期视图下按规划日分组的时间轴，每个规划日内部各自把正在计时的条目置顶。
 *
 * 分组键取自条目自身的规划日字符串，与 [agendaEntries] 的排序口径一致，因此同一次
 * 数据读取里日期分组与时间轴顺序不会各说各话。
 */
fun AttentionState.agendaDayTimelines(): List<AgendaDayTimeline> = schedules
    .asSequence()
    .map { it.planningDate }
    .distinct()
    .sorted()
    .map { date -> AgendaDayTimeline(date, agendaTimeline(LocalDate.parse(date))) }
    .toList()

/**
 * 这条日程条目自己的计划时长是否超出这一天的每日容量。
 *
 * 口径与 [capacitySummary] 一致：正数预计时长优先，否则回退到完整有效的时间段。
 * 每日容量只是可解释的提示，因此这个判定不会拦住保存日程或保存实际投入。
 * 未设置每日容量时没有超载可言。
 */
fun AttentionState.exceedsDailyCapacity(entry: ScheduleEntry): Boolean {
    val capacity = settings.dailyCapacityMinutes ?: return false
    return (entry.plannedMinutes() ?: 0) > capacity
}

fun AttentionState.targetChildren(parentId: String?, onDate: LocalDate? = null): List<Target> = targets
    .filter { target ->
        !target.archived && (onDate?.let { parentAt(target.id, it) == parentId } ?: (target.parentId == parentId))
    }
    .sortedBy { it.sortOrder }

fun AttentionState.descendantIds(targetId: String): Set<String> {
    val result = mutableSetOf(targetId)
    var changed = true
    while (changed) {
        changed = false
        targets.filter { it.parentId in result }.forEach { changed = result.add(it.id) || changed }
    }
    return result
}

fun AttentionState.descendantIdsAt(targetId: String, onDate: LocalDate): Set<String> = targets
    .filter { isDescendantAt(it.id, targetId, onDate) }
    .mapTo(mutableSetOf()) { it.id }

private fun AttentionState.isDescendantAt(candidateId: String, ancestorId: String, date: LocalDate): Boolean {
    var current: String? = candidateId
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        if (current == ancestorId) return true
        current = parentAt(current, date)
    }
    return false
}

fun AttentionState.directMinutes(targetId: String, date: String? = null): Int = timeEntries
    .asSequence()
    .filter { it.targetId == targetId && (date == null || it.planningDate == date) }
    .sumOf { it.durationMinutes }

fun AttentionState.subtreeMinutes(targetId: String, date: String? = null): Int {
    return if (date == null) {
        timeEntries
            .filter { it.targetId != null }
            .filter { isDescendantAt(it.targetId!!, targetId, LocalDate.parse(it.planningDate)) }
            .sumOf { it.durationMinutes }
    } else {
        val parsed = LocalDate.parse(date)
        timeEntries.filter { it.planningDate == date && it.targetId != null && isDescendantAt(it.targetId, targetId, parsed) }
            .sumOf { it.durationMinutes }
    }
}

fun AttentionState.periodRange(
    date: LocalDate,
    cadence: GoalCadence,
): ClosedRange<LocalDate> = when (cadence) {
    GoalCadence.DAILY -> date..date
    GoalCadence.WEEKLY -> {
        val start = date.minusDays(((date.dayOfWeek.value - settings.weekStartDay + 7) % 7).toLong())
        start..start.plusDays(6)
    }
    GoalCadence.MONTHLY -> date.withDayOfMonth(1)..date.withDayOfMonth(date.lengthOfMonth())
    GoalCadence.ONE_TIME -> date..date
}

fun AttentionState.progressRange(stage: GoalStage, onDate: LocalDate): ClosedRange<LocalDate> {
    val effective = effectiveStage(stage, onDate)
    val start = LocalDate.parse(effective.startDate)
    if (onDate.isBefore(start)) return start..start
    if (effective.cadence != GoalCadence.ONE_TIME) return periodRange(onDate, effective.cadence)
    val due = effective.dueDate?.let(LocalDate::parse)
    val nextStageStart = goalStagesForTarget(stage.targetId)
        .asSequence()
        .filter { it.cadence == GoalCadence.ONE_TIME && it.id != stage.id }
        .map { LocalDate.parse(it.startDate) }
        .filter { it.isAfter(start) }
        .minOrNull()
    val naturalEnd = listOfNotNull(due, nextStageStart?.minusDays(1)).minOrNull()
    val end = minOf(onDate, naturalEnd ?: onDate).let { maxOf(start, it) }
    return start..end
}

fun AttentionState.progress(stage: GoalStage, onDate: LocalDate): GoalProgress {
    val effective = effectiveStage(stage, onDate)
    if (onDate.isBefore(LocalDate.parse(effective.startDate))) return GoalProgress(0, 0, 0, 0, false)
    val actual = entriesForProgress(stage, onDate).sumOf { it.durationMinutes }
    val gap = (effective.targetMinutes - actual).coerceAtLeast(0)
    return GoalProgress(effective.targetMinutes, actual, gap, (actual - effective.targetMinutes).coerceAtLeast(0), actual >= effective.targetMinutes)
}

private fun AttentionState.entriesForProgress(
    stage: GoalStage,
    onDate: LocalDate,
    excludedEntryIds: Set<String> = emptySet(),
): List<TimeEntry> {
    val effective = effectiveStage(stage, onDate)
    if (onDate.isBefore(LocalDate.parse(effective.startDate))) return emptyList()
    val range = progressRange(stage, onDate)
    val effectiveStart = LocalDate.parse(effective.startDate)
    return timeEntries.filter { it.id !in excludedEntryIds && it.targetId != null }
        .filter { LocalDate.parse(it.planningDate) in range }
        // A stage or future rule may begin in the middle of a calendar cycle.
        // Minutes recorded before that effective start belong to the previous
        // commitment and must not complete this stage's current period.
        .filter { !LocalDate.parse(it.planningDate).isBefore(effectiveStart) }
        .filter { isDescendantAt(it.targetId!!, effective.targetId, LocalDate.parse(it.planningDate)) }
}

private fun AttentionState.progressForSummary(
    stage: GoalStage,
    onDate: LocalDate,
    claimedEntryIds: MutableSet<String>,
): GoalProgress {
    val effective = effectiveStage(stage, onDate)
    val targetMinutes = effective.targetMinutes
    val start = LocalDate.parse(effective.startDate)
    if (onDate.isBefore(start)) return GoalProgress(targetMinutes, 0, targetMinutes, 0, false)

    val matchingEntries = entriesForProgress(stage, onDate, claimedEntryIds)
    claimedEntryIds += matchingEntries.map { it.id }
    val actual = matchingEntries.sumOf { it.durationMinutes }
    val gap = (targetMinutes - actual).coerceAtLeast(0)
    return GoalProgress(targetMinutes, actual, gap, (actual - targetMinutes).coerceAtLeast(0), actual >= targetMinutes)
}

/**
 * Returns the target-level roll-up for all one-time stages owned by a target.
 * Each matching time entry is claimed by at most one stage in this summary,
 * while hierarchy membership remains date-aware so historical target moves are
 * evaluated against the tree that existed on the entry's planning date.
 */
fun AttentionState.goalSummary(targetId: String, onDate: LocalDate): GoalSummary {
    val claimedEntryIds = mutableSetOf<String>()
    val stageProgresses = goalStagesForTarget(targetId)
        .filter { it.cadence == GoalCadence.ONE_TIME }
        .map { stage ->
            GoalStageSummary(stage, progressForSummary(stage, onDate, claimedEntryIds))
        }
    val targetMinutes = stageProgresses.sumOf { it.progress.targetMinutes }
    val actualMinutes = stageProgresses.sumOf { it.progress.actualMinutes }
    val gapMinutes = stageProgresses.sumOf { it.progress.gapMinutes }
    val excessMinutes = stageProgresses.sumOf { it.progress.excessMinutes }
    return GoalSummary(
        targetId = targetId,
        stageProgresses = stageProgresses,
        targetMinutes = targetMinutes,
        actualMinutes = actualMinutes,
        gapMinutes = gapMinutes,
        excessMinutes = excessMinutes,
        completed = stageProgresses.isNotEmpty() && stageProgresses.all { it.progress.completed },
    )
}

fun AttentionState.effectiveStage(stage: GoalStage, onDate: LocalDate): GoalStage {
    val rule = goalRuleAt(stage.targetId, stage.id, onDate)
        ?: return stage
    return stage.copy(
        cadence = rule.cadence,
        targetMinutes = rule.targetMinutes,
        startDate = rule.effectiveFrom,
        dueDate = rule.dueDate,
    )
}

fun AttentionState.capacitySummary(date: String): CapacitySummary {
    val capacity = settings.dailyCapacityMinutes
    val used = schedules
        .asSequence()
        .filter { it.planningDate == date }
        .sumOf(ScheduleEntry::capacityMinutes)
    return CapacitySummary(capacity, used, capacity != null && used > capacity)
}

/**
 * 计划时长：用户为这条日程承诺的分钟数，没有承诺时返回 null。
 *
 * 口径与每日容量一致：正数预计时长是用户的计划值，优先于可选时间段；无效的
 * 预计时长回退到完整有效的时间段，部分或无效时间段不算承诺。因此容量计算与
 * 「计划时长」说的是同一件事，二者不会给出不同的答案。
 */
fun ScheduleEntry.plannedMinutes(): Int? = estimatedMinutes
    ?.takeIf { it > 0 }
    ?: if (
        startMinute != null &&
        endMinute != null &&
        startMinute in 0 until 1440 &&
        endMinute in 1..1440 &&
        endMinute > startMinute
    ) {
        endMinute - startMinute
    } else {
        null
    }

/**
 * Returns the minutes reserved by one saved schedule entry.
 *
 * A schedule entry without a planned duration reserves nothing, which keeps
 * malformed imported entries from reducing a day's used capacity below zero.
 */
private fun ScheduleEntry.capacityMinutes(): Int = plannedMinutes() ?: 0

fun AttentionState.periodStats(date: LocalDate, cadence: GoalCadence): PeriodStats {
    val range = periodRange(date, cadence)
    val entries = timeEntries.filter { LocalDate.parse(it.planningDate) in range }
    val actual = entries.sumOf { it.durationMinutes }
    val unowned = entries.filter { it.targetId == null }.sumOf { it.durationMinutes }
    val byTarget = entries.filter { it.targetId != null }
        .groupBy { it.targetId!! }
        .mapValues { (_, values) -> values.sumOf { it.durationMinutes } }
    val subtreeByTarget = targets.associate { target ->
        target.id to entries.asSequence()
            .filter { entry ->
                entry.targetId != null && isDescendantAt(
                    entry.targetId,
                    target.id,
                    LocalDate.parse(entry.planningDate),
                )
            }
            .sumOf { it.durationMinutes }
    }.filterValues { it > 0 }
    val targetMinutes = goalStages
        .filter { it.cadence == cadence && LocalDate.parse(it.startDate) <= range.endInclusive }
        .sumOf { stage ->
            when (stage.cadence) {
                GoalCadence.DAILY -> stage.targetMinutes * (range.endInclusive.toEpochDay() - maxOf(range.start.toEpochDay(), LocalDate.parse(stage.startDate).toEpochDay()) + 1).toInt()
                GoalCadence.ONE_TIME -> if (stage.dueDate == null || LocalDate.parse(stage.dueDate) >= range.start) stage.targetMinutes else 0
                else -> stage.targetMinutes
            }
        }
    val migrationMinutes = migrations.filterNot { it.cancelled }.filter {
        val start = LocalDate.parse(it.destinationStartDate)
        val end = it.destinationEndDate?.let(LocalDate::parse) ?: start
        start <= range.endInclusive && end >= range.start
    }.sumOf { it.minutes }
    return PeriodStats(
        range = range,
        targetMinutes = targetMinutes,
        actualMinutes = actual,
        unownedMinutes = unowned,
        migrationMinutes = migrationMinutes,
        excessMinutes = (actual - targetMinutes).coerceAtLeast(0),
        byTargetMinutes = byTarget,
        subtreeByTargetMinutes = subtreeByTarget,
    )
}

fun AttentionState.validTarget(targetId: String?): Boolean = targetId == null || targets.any { it.id == targetId }

private fun AttentionState.validRecordTarget(targetId: String?): Boolean =
    targetId == null || targets.any { it.id == targetId && !it.archived }

/**
 * Validates the domain invariant shared by imported and persisted timer state.
 * Adapters call this pure function before replacing their storage rows.
 */
fun AttentionState.validateActiveTimerTarget() {
    val timerTargetId = activeTimer?.targetId ?: return
    val target = targets.singleOrNull { it.id == timerTargetId }
        ?: error("活动计时器目标不存在")
    require(!target.archived) { "活动计时器不能归入归档目标" }
}

/** Stable label used by every timer projection, including unowned activity. */
fun AttentionState.timerTargetLabel(targetId: String?): String =
    targetId
        ?.let { id -> targets.firstOrNull { it.id == id }?.title }
        ?.takeIf(String::isNotBlank)
        ?: "未归属活动"

/**
 * Milliseconds this timer has actually been running as of [now].
 *
 * Paused time freezes at the accumulated total; a running timer adds the segment
 * opened when it was last resumed, and a clock that jumped backwards adds nothing.
 * The caller supplies [now] so every projection and test reads the same value from
 * the same rule instead of re-deriving it from the timer fields.
 */
fun ActiveTimer.elapsedMillis(now: Instant): Long =
    if (paused) {
        accumulatedMillis
    } else {
        accumulatedMillis + (now.toEpochMilli() - startedAtEpochMillis).coerceAtLeast(0)
    }

fun AttentionState.addTarget(title: String, parentId: String? = null): AttentionState {
    require(title.isNotBlank())
    require(parentId == null || targets.any { it.id == parentId })
    val siblingOrder = targets.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
    return copy(targets = targets + Target(title = title.trim(), parentId = parentId, sortOrder = siblingOrder))
}

fun AttentionState.renameTarget(targetId: String, title: String): AttentionState {
    require(title.isNotBlank())
    require(targets.any { it.id == targetId })
    return copy(targets = targets.map { if (it.id == targetId) it.copy(title = title.trim()) else it })
}

fun AttentionState.setTargetExpanded(targetId: String, expanded: Boolean): AttentionState = copy(
    targets = targets.map { if (it.id == targetId) it.copy(expanded = expanded) else it },
)

fun AttentionState.moveTarget(targetId: String, newParentId: String?): AttentionState {
    require(targets.any { it.id == targetId })
    require(newParentId == null || targets.any { it.id == newParentId })
    require(newParentId == null || newParentId !in descendantIds(targetId))
    val target = targets.first { it.id == targetId }
    if (target.parentId == newParentId) return this
    val nextOrder = targets.filter { it.parentId == newParentId && it.id != targetId }
        .maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
    return copy(targets = targets.map { if (it.id == targetId) it.copy(parentId = newParentId, sortOrder = nextOrder) else it })
}

fun AttentionState.reorderTarget(targetId: String, newIndex: Int): AttentionState {
    val target = targets.firstOrNull { it.id == targetId } ?: error("目标不存在")
    val siblings = targets.filter { it.parentId == target.parentId }.sortedBy { it.sortOrder }.toMutableList()
    require(newIndex in siblings.indices)
    siblings.remove(target)
    siblings.add(newIndex, target)
    val order = siblings.mapIndexed { index, item -> item.id to index }.toMap()
    return copy(targets = targets.map { item -> order[item.id]?.let { item.copy(sortOrder = it) } ?: item })
}

fun AttentionState.archiveTarget(targetId: String, archived: Boolean = true): AttentionState {
    val nextTimer = if (archived && activeTimer?.targetId == targetId) {
        activeTimer.copy(targetId = null)
    } else {
        activeTimer
    }
    return copy(
        targets = targets.map { if (it.id == targetId) it.copy(archived = archived) else it },
        activeTimer = nextTimer,
    )
}

/** Restores one or more archived targets without changing their hierarchy or ordering. */
fun AttentionState.restoreTargets(targetIds: Set<String>): AttentionState {
    if (targetIds.isEmpty()) return this
    require(targetIds.all { id -> targets.any { it.id == id } }) { "目标不存在" }
    // A restored descendant must remain reachable from the default tree. Restore
    // any archived ancestors along its parent chain as part of the same action.
    val idsToRestore = targetIds.toMutableSet()
    var changed = true
    while (changed) {
        changed = false
        targets.filter { it.id in idsToRestore }.forEach { target ->
            val parentId = target.parentId
            if (parentId != null && targets.any { it.id == parentId && it.archived }) {
                changed = idsToRestore.add(parentId) || changed
            }
        }
    }
    return copy(targets = targets.map { target ->
        if (target.id in idsToRestore) target.copy(archived = false) else target
    })
}

/** Returns a deduplicated impact summary for deleting the selected target subtrees. */
fun AttentionState.targetDeletionImpact(targetIds: Set<String>): TargetDeletionImpact {
    val ids = targetIds.flatMap(::descendantIds).toSet()
    return TargetDeletionImpact(
        targetCount = ids.count { id -> targets.any { it.id == id } },
        timeEntryCount = timeEntries.count { it.targetId in ids },
        scheduleEntryCount = schedules.count { it.targetId in ids },
        recurrenceRuleCount = recurrenceRules.count { it.targetId in ids },
        goalStageCount = goalStages.count { it.targetId in ids },
        futureGoalRuleCount = futureGoalRules.count { it.targetId in ids },
        migrationCount = migrations.count { it.targetId in ids || goalStages.any { stage -> stage.id == it.sourceStageId && stage.targetId in ids } },
        targetMoveCount = targetMoves.count { it.targetId in ids || it.parentId in ids },
        activeTimerCount = if (activeTimer?.targetId in ids) 1 else 0,
    )
}

fun AttentionState.deleteTargetRelations(targetIds: Set<String>): AttentionState {
    val ids = targetIds.flatMap(::descendantIds).toSet()
    val deletedStageIds = goalStages.filter { it.targetId in ids }.map { it.id }.toSet()
    return copy(
        targets = targets.filterNot { it.id in ids },
        goalStages = goalStages.filterNot { it.targetId in ids },
        recurrenceRules = recurrenceRules.map { if (it.targetId in ids) it.copy(targetId = null) else it },
        timeEntries = timeEntries.map { if (it.targetId in ids) it.copy(targetId = null) else it },
        schedules = schedules.map { if (it.targetId in ids) it.copy(targetId = null) else it },
        migrations = migrations.filterNot { it.targetId in ids || it.sourceStageId in deletedStageIds },
        futureGoalRules = futureGoalRules.filterNot { it.targetId in ids || it.stageId in deletedStageIds },
        targetMoves = targetMoves
            .filterNot { it.targetId in ids }
            .map { move -> if (move.parentId != null && move.parentId in ids) move.copy(parentId = null) else move },
        activeTimer = activeTimer?.let { timer ->
            if (timer.targetId in ids) timer.copy(targetId = null) else timer
        },
    ).recalculateExperience()
}

fun AttentionState.addGoalStage(
    targetId: String,
    cadence: GoalCadence,
    targetMinutes: Int,
    startDate: String,
    dueDate: String? = null,
): AttentionState {
    require(targets.any { it.id == targetId && !it.archived }) { "目标必须未归档" }
    require(targetMinutes > 0)
    require(cadence != GoalCadence.ONE_TIME || goalStages.none { it.targetId == targetId && it.cadence == GoalCadence.ONE_TIME }) {
        "已有一次性阶段，请使用追加阶段"
    }
    val parsedStart = LocalDate.parse(startDate)
    val parsedDue = dueDate?.let(LocalDate::parse)
    require(parsedDue == null || !parsedDue.isBefore(parsedStart)) { "截止日期不能早于开始日期" }
    return copy(goalStages = goalStages + GoalStage(targetId = targetId, cadence = cadence, targetMinutes = targetMinutes, startDate = startDate, dueDate = dueDate))
}

/**
 * Returns a target's stages in the order in which their planning dates begin.
 * The stable stage ID breaks ties so the result remains deterministic after a
 * backup restore or a Room query.
 */
fun AttentionState.goalStagesForTarget(targetId: String): List<GoalStage> = goalStages
    .filter { it.targetId == targetId }
    .sortedWith(compareBy<GoalStage> { LocalDate.parse(it.startDate) }.thenBy { it.id })

/**
 * Appends a new one-time stage after the target's latest stage is achieved.
 * Validation happens before creating the new stage, so a rejected command
 * leaves every existing collection unchanged.
 */
fun AttentionState.appendOneTimeGoalStage(
    targetId: String,
    targetMinutes: Int,
    startDate: String,
    dueDate: String? = null,
): AttentionState {
    require(targets.any { it.id == targetId && !it.archived }) { "目标必须未归档" }
    require(targetMinutes > 0) { "目标分钟必须为正整数" }

    val parsedStart = LocalDate.parse(startDate)
    val parsedDue = dueDate?.let(LocalDate::parse)
    require(parsedDue == null || !parsedDue.isBefore(parsedStart)) { "截止日期不能早于开始日期" }

    val previous = goalStagesForTarget(targetId).lastOrNull()
    require(previous != null) { "目标没有可追加的一次性阶段" }
    require(previous.cadence == GoalCadence.ONE_TIME) { "只能在一次性阶段后追加" }
    require(parsedStart.isAfter(LocalDate.parse(previous.startDate))) { "新阶段开始日期必须晚于上一阶段" }
    require(progress(previous, parsedStart).completed) { "上一阶段尚未达成" }

    return copy(
        goalStages = goalStages + GoalStage(
            targetId = targetId,
            cadence = GoalCadence.ONE_TIME,
            targetMinutes = targetMinutes,
            startDate = startDate,
            dueDate = dueDate,
        ),
    )
}

/**
 * 追加一笔实际投入。
 *
 * [scheduleEntryId] 非空时这笔记录由某条日程条目产生，这条链接的判定只在 `:domain` 里做。
 * [backfillRequestId] 只属于事后补记（[TimeEntrySource.MANUAL]）：它标识「这一次提交」，
 * 重放同一次提交时用它避免重复记账，但绝不用来判定这条日程是否已经有投入。
 */
fun AttentionState.addTimeEntry(
    planningDate: String,
    durationMinutes: Int,
    targetId: String? = null,
    source: TimeEntrySource = TimeEntrySource.MANUAL,
    occurredAtEpochMillis: Long? = null,
    note: String = "",
    scheduleEntryId: String? = null,
    backfillRequestId: String? = null,
): AttentionState {
    LocalDate.parse(planningDate)
    require(durationMinutes > 0) { "时间记录时长必须为正数" }
    require(validRecordTarget(targetId)) { "时间记录只能归入未归档目标" }
    require(scheduleEntryId == null || schedules.any { it.id == scheduleEntryId }) { "时间记录只能关联存在的日程条目" }
    require(backfillRequestId == null || source == TimeEntrySource.MANUAL) { "只有事后补记带提交意图标识" }
    require(backfillRequestId == null || backfillRequestId.isNotBlank()) { "提交意图标识不能为空" }
    return copy(timeEntries = timeEntries + TimeEntry(planningDate = planningDate, durationMinutes = durationMinutes, targetId = targetId, source = source, occurredAtEpochMillis = occurredAtEpochMillis, note = note, scheduleEntryId = scheduleEntryId, backfillRequestId = backfillRequestId))
        .recalculateExperience()
}

/**
 * 日程完成确认已经为这条日程条目记下的那笔实际投入。
 *
 * 这是「重复确认同一个动作」的幂等判据：同一条日程重复确认仍然是同一个动作，不该产生第二笔。
 * 它只认 [TimeEntrySource.SCHEDULE_CONFIRMED]——事后补记和计时结算是各自独立的事实，
 * 既不能让确认跳过记账，也不能反过来被确认吞掉。
 */
fun AttentionState.confirmedInvestmentForSchedule(scheduleEntryId: String): TimeEntry? =
    timeEntries.firstOrNull { it.scheduleEntryId == scheduleEntryId && it.source == TimeEntrySource.SCHEDULE_CONFIRMED }

/** [confirmedInvestmentForSchedule] 的布尔形式。 */
fun AttentionState.hasConfirmedInvestmentFor(scheduleEntryId: String): Boolean =
    confirmedInvestmentForSchedule(scheduleEntryId) != null

/**
 * 这条日程条目已经记录在案的事后补记，按提交先后排列。
 *
 * 一条日程可以有多笔补记：用户每显式提交一次都是一个新的事实，都必须被记录下来。
 * 页面投影用它在日程条目上展示「已补记多久」，而不是用它决定补记入口是否出现。
 */
fun AttentionState.backfillsForSchedule(scheduleEntryId: String): List<TimeEntry> =
    timeEntries.filter { it.scheduleEntryId == scheduleEntryId && it.source == TimeEntrySource.MANUAL }

/**
 * 这一次事后补记提交是否已经应用过。
 *
 * 重放判据：同一次保存（重试、双击、事务重放）沿用同一个提交意图标识，
 * 因此第二次到达时命中已有记录，不会再记一笔。新的补记用新的标识，必然被记录。
 */
fun AttentionState.appliedBackfill(requestId: String): TimeEntry? =
    timeEntries.firstOrNull { it.backfillRequestId == requestId }

fun AttentionState.editTimeEntry(entryId: String, durationMinutes: Int, targetId: String?, note: String = ""): AttentionState {
    require(durationMinutes > 0 && validRecordTarget(targetId)) { "时间记录只能归入未归档目标" }
    require(timeEntries.any { it.id == entryId }) { "时间记录不存在" }
    return copy(timeEntries = timeEntries.map { if (it.id == entryId) it.copy(durationMinutes = durationMinutes, targetId = targetId, note = note) else it })
        .recalculateExperience()
}

fun AttentionState.deleteTimeEntry(entryId: String): AttentionState = copy(timeEntries = timeEntries.filterNot { it.id == entryId })
    .recalculateExperience()

/**
 * Reads the time records of one planning day for display. Records whose linked schedule entry no
 * longer exists are still returned; the read model reports the missing schedule instead of
 * throwing or silently dropping the record.
 */
fun AttentionState.timeEntryRows(planningDate: String? = null): List<TimeEntryRow> = timeEntries
    .filter { planningDate == null || it.planningDate == planningDate }
    .map { entry ->
        TimeEntryRow(
            entry = entry,
            source = entry.source,
            schedule = entry.scheduleEntryId?.let { id -> schedules.firstOrNull { it.id == id } },
        )
    }

fun AttentionState.assignUnowned(entryIds: Set<String>, targetId: String): AttentionState {
    if (entryIds.isEmpty()) return this
    require(targets.any { it.id == targetId && !it.archived }) { "时间记录只能归入未归档目标" }
    require(entryIds.all { id -> timeEntries.any { it.id == id && it.targetId == null } }) {
        "只能归入存在且未归属的时间记录"
    }
    return copy(timeEntries = timeEntries.map {
        if (it.id in entryIds && it.targetId == null) it.copy(targetId = targetId) else it
    }).recalculateExperience()
}

fun AttentionState.addSchedule(entry: ScheduleEntry): AttentionState {
    validateScheduleEntry(entry)
    return copy(schedules = schedules + entry.copy(title = entry.title.trim()))
}

fun AttentionState.updateSchedule(entry: ScheduleEntry): AttentionState {
    require(schedules.any { it.id == entry.id }) { "日程条目不存在" }
    validateScheduleEntry(entry)
    return copy(schedules = schedules.map { if (it.id == entry.id) entry.copy(title = entry.title.trim()) else it })
}

/** Toggles only a schedule entry's completion state. Completion is not actual time invested. */
fun AttentionState.toggleScheduleCompleted(entryId: String): AttentionState {
    require(schedules.any { it.id == entryId }) { "日程条目不存在" }
    return copy(schedules = schedules.map { entry ->
        if (entry.id == entryId) entry.copy(completed = !entry.completed) else entry
    })
}

/**
 * 完成一条日程条目，并记下它声称的那笔实际投入。以这种方式产生的记录来源是
 * `SCHEDULE_CONFIRMED`：它没有精确起止时刻，因为用户是按计划确认的，不是计时测出来的。
 *
 * 同一条日程重复确认是同一个动作，因此幂等：已经有 [TimeEntrySource.SCHEDULE_CONFIRMED]
 * 记录时只保证完成标记，不再补第二笔，投入经验与达成里程碑因此不会被重复奖励。
 * 这条判据不认事后补记与计时结算——那是各自独立的事实，不能互相吞掉。
 */
fun AttentionState.confirmScheduleCompleted(entryId: String, durationMinutes: Int? = null): AttentionState {
    val entry = schedules.firstOrNull { it.id == entryId } ?: throw IllegalArgumentException("日程条目不存在")
    val completedSchedules = schedules.map { schedule -> if (schedule.id == entryId) schedule.copy(completed = true) else schedule }
    if (hasConfirmedInvestmentFor(entryId)) return copy(schedules = completedSchedules).recalculateExperience()
    val rangeMinutes = if (entry.startMinute != null && entry.endMinute != null) {
        entry.endMinute - entry.startMinute
    } else {
        null
    }
    val minutes = durationMinutes
        ?: entry.estimatedMinutes
        ?: rangeMinutes
        ?: throw IllegalArgumentException("日程条目缺少预计时长，无法确认实际投入")
    require(minutes > 0) { "时间记录时长必须为正数" }
    require(validRecordTarget(entry.targetId)) { "时间记录只能归入未归档目标" }
    return copy(
        schedules = completedSchedules,
        timeEntries = timeEntries + TimeEntry(
            planningDate = entry.planningDate,
            durationMinutes = minutes,
            targetId = entry.targetId,
            source = TimeEntrySource.SCHEDULE_CONFIRMED,
            occurredAtEpochMillis = null,
            scheduleEntryId = entryId,
        ),
    ).recalculateExperience()
}

/** 与这条日程关联的实际投入合计：只统计 `scheduleEntryId` 指向它的时间记录。 */
fun AttentionState.scheduleInvestedMinutes(entryId: String): Int =
    timeEntries.filter { it.scheduleEntryId == entryId }.sumOf(TimeEntry::durationMinutes)

/**
 * 达到计划时长才算完成。没有计划时长的日程不由系统猜测，它保持用户自己的判断。
 */
fun AttentionState.isScheduleInvestmentMet(entryId: String): Boolean {
    val planned = schedules.firstOrNull { it.id == entryId }?.plannedMinutes() ?: return false
    return scheduleInvestedMinutes(entryId) >= planned
}

/**
 * 把已达标且仍未完成的日程标记完成。
 *
 * 这是幂等的：已经完成的日程不再变化，并且不新增任何时间记录——完成状态只是
 * 对既有实际投入的读数，投入来源与它结算时写入的来源保持一致。
 */
fun AttentionState.completeScheduleMetByInvestment(entryId: String?): AttentionState {
    if (entryId == null || !isScheduleInvestmentMet(entryId)) return this
    if (schedules.first { it.id == entryId }.completed) return this
    return copy(schedules = schedules.map { entry ->
        if (entry.id == entryId) entry.copy(completed = true) else entry
    })
}

/**
 * 用户在停止计时的结果面板上明确决定「这条做完了」时，把日程标记完成。
 *
 * 与 [completeScheduleMetByInvestment] 的区别在于判据：那个由已投入分钟数结算，只在达到
 * 计划时长时改变状态；这个由用户的决定驱动，因此**不足计划时长也能真完成**。
 *
 * 它是幂等的，而且只翻完成位、不新增任何时间记录：那一笔投入已经在停止计时时写下，
 * 再建第二笔会把它算两遍。重复点同一处不会把它翻回未完成——那是 [toggleScheduleCompleted]
 * 的语义，两条路径在页面上互斥。
 */
fun AttentionState.markScheduleCompleted(entryId: String): AttentionState {
    require(schedules.any { it.id == entryId }) { "日程条目不存在" }
    if (schedules.first { it.id == entryId }.completed) return this
    return copy(schedules = schedules.map { entry ->
        if (entry.id == entryId) entry.copy(completed = true) else entry
    })
}

private fun AttentionState.validateScheduleEntry(entry: ScheduleEntry) {
    require(entry.title.isNotBlank()) { "日程标题不能为空" }
    LocalDate.parse(entry.planningDate)
    require(entry.startMinute == null || entry.startMinute in 0 until 1440) { "开始时间必须在 00:00 到 23:59 之间" }
    require(entry.endMinute == null || entry.endMinute in 1..1440) { "结束时间必须在 00:01 到次日 00:00 之间" }
    require((entry.startMinute == null) == (entry.endMinute == null)) { "时间段必须同时填写开始和结束时间" }
    if (entry.startMinute != null && entry.endMinute != null) {
        require(entry.endMinute > entry.startMinute) { "结束时间必须晚于开始时间" }
    }
    require(entry.estimatedMinutes == null || entry.estimatedMinutes > 0) { "预计时长必须为正数" }
    require(validTarget(entry.targetId)) { "日程只能关联存在的计划" }
}

fun AttentionState.addRecurrence(rule: RecurrenceRule): AttentionState {
    require(rule.title.isNotBlank()) { "重复日程标题不能为空" }
    val startDate = LocalDate.parse(rule.startDate)
    val untilDate = rule.untilDate?.let(LocalDate::parse)
    require(untilDate == null || !untilDate.isBefore(startDate)) { "结束规划日不能早于开始规划日" }
    require(rule.estimatedMinutes == null || rule.estimatedMinutes > 0) { "预计时长必须为正数" }
    require(rule.reminderMinuteOfDay == null || rule.reminderMinuteOfDay in 0 until 1440) {
        "提醒时间必须在 00:00 到 23:59 之间"
    }
    require(validRecordTarget(rule.targetId)) { "重复日程只能关联未归档计划" }
    require(recurrenceRules.none { it.id == rule.id }) { "重复规则 ID 已存在" }

    val normalizedWeekdays = rule.weekdays.distinct().sorted()
    when (rule.frequency) {
        ScheduleFrequency.DAILY -> {
            require(rule.weekdays.isEmpty()) { "每日规则不能设置星期集合" }
            require(rule.dayOfMonth == null) { "每日规则不能设置月内日期" }
        }
        ScheduleFrequency.WEEKLY -> {
            require(normalizedWeekdays.isNotEmpty()) { "每周规则至少选择一个星期" }
            require(normalizedWeekdays.all { it in 1..7 }) { "星期必须在 1 到 7 之间" }
            require(rule.dayOfMonth == null) { "每周规则不能设置月内日期" }
        }
        ScheduleFrequency.MONTHLY -> {
            require(rule.weekdays.isEmpty()) { "每月规则不能设置星期集合" }
            require(rule.dayOfMonth in 1..31) { "月内日期必须在 1 到 31 之间" }
        }
    }
    return copy(
        recurrenceRules = recurrenceRules + rule.copy(
            title = rule.title.trim(),
            weekdays = normalizedWeekdays,
        ),
    )
}

fun AttentionState.stopRecurrence(ruleId: String): AttentionState = copy(
    recurrenceRules = recurrenceRules.map { if (it.id == ruleId) it.copy(active = false) else it },
)

fun reminderEpochMillis(
    planningDate: LocalDate,
    minuteOfDay: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    require(minuteOfDay in 0 until 1440) { "提醒时间必须在 00:00 到 23:59 之间" }
    return planningDate.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
}

fun AttentionState.occurrences(
    rule: RecurrenceRule,
    through: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ScheduleEntry> {
    val start = LocalDate.parse(rule.startDate)
    val end = minOf(through, rule.untilDate?.let(LocalDate::parse) ?: through)
    if (!rule.active || start.isAfter(end)) return emptyList()
    return generateSequence(start) { it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }
        .filter { date ->
            when (rule.frequency) {
                ScheduleFrequency.DAILY -> true
                ScheduleFrequency.WEEKLY -> date.dayOfWeek.value in rule.weekdays
                ScheduleFrequency.MONTHLY -> date.dayOfMonth == (rule.dayOfMonth ?: start.dayOfMonth)
            }
        }
        .map { date ->
            ScheduleEntry(
                planningDate = date.toString(),
                title = rule.title,
                estimatedMinutes = rule.estimatedMinutes,
                targetId = rule.targetId,
                note = rule.note,
                recurrenceRuleId = rule.id,
                reminderEpochMillis = rule.reminderMinuteOfDay?.let { minute ->
                    reminderEpochMillis(date, minute, zone)
                },
            )
        }
        .toList()
}

fun AttentionState.availableMigrationMinutes(stage: GoalStage, onDate: LocalDate): Int {
    require(goalStages.any { it.id == stage.id && it.targetId == stage.targetId })
    val reserved = migrations
        .filter { it.sourceStageId == stage.id && !it.cancelled }
        .sumOf { it.minutes }
    return (progress(stage, onDate).gapMinutes - reserved).coerceAtLeast(0)
}

fun AttentionState.addMigration(migration: Migration, onDate: LocalDate): AttentionState {
    require(migration.minutes > 0)
    require(!migration.cancelled)
    require(targets.any { it.id == migration.targetId && !it.archived })
    val sourceStage = goalStages.firstOrNull { it.id == migration.sourceStageId && it.targetId == migration.targetId }
        ?: error("来源目标阶段不存在")
    val destinationStart = LocalDate.parse(migration.destinationStartDate)
    val destinationEnd = migration.destinationEndDate?.let(LocalDate::parse)
    require(destinationEnd == null || !destinationEnd.isBefore(destinationStart))
    require(destinationStart.isAfter(progressRange(sourceStage, onDate).endInclusive))
    require(migration.minutes <= availableMigrationMinutes(sourceStage, onDate))
    return copy(migrations = migrations + migration)
}

fun AttentionState.updateMigration(migration: Migration, onDate: LocalDate): AttentionState {
    val existing = migrations.firstOrNull { it.id == migration.id } ?: error("迁移记录不存在")
    require(!existing.cancelled)
    val withoutExisting = copy(migrations = migrations - existing)
    return withoutExisting.addMigration(migration, onDate)
}

fun AttentionState.cancelMigration(migrationId: String): AttentionState {
    require(migrations.any { it.id == migrationId })
    return copy(migrations = migrations.map { if (it.id == migrationId) it.copy(cancelled = true) else it })
}

fun AttentionState.gapFor(stage: GoalStage, onDate: LocalDate): Int = progress(stage, onDate).gapMinutes

/**
 * Adds a future target commitment. [notBefore] is supplied by the caller's
 * current planning day so tests and backup replay do not read the system clock.
 */
fun AttentionState.addFutureGoalRule(
    rule: FutureGoalRule,
    notBefore: LocalDate,
): AttentionState {
    validateFutureGoalRule(rule, notBefore)
    require(futureGoalRules.none { it.id == rule.id }) { "未来目标规则 ID 已存在" }
    require(futureGoalRules.none { it.sameScopeAs(rule) && it.effectiveFrom == rule.effectiveFrom }) {
        "同一目标和阶段在同一生效日只能有一条未来规则"
    }
    return copy(futureGoalRules = futureGoalRules + rule)
}

fun AttentionState.updateFutureGoalRule(
    rule: FutureGoalRule,
    notBefore: LocalDate,
): AttentionState {
    val existing = futureGoalRules.firstOrNull { it.id == rule.id } ?: error("未来目标规则不存在")
    require(existing.targetId == rule.targetId && existing.stageId == rule.stageId) {
        "未来目标规则的目标和阶段不能修改"
    }
    require(LocalDate.parse(existing.effectiveFrom).isAfter(notBefore)) {
        "已经生效的未来目标规则不能修改"
    }
    validateFutureGoalRule(rule, notBefore)
    require(futureGoalRules.none { it.id != rule.id && it.sameScopeAs(rule) && it.effectiveFrom == rule.effectiveFrom }) {
        "同一目标和阶段在同一生效日只能有一条未来规则"
    }
    return copy(futureGoalRules = futureGoalRules.map { if (it.id == rule.id) rule else it })
}

fun AttentionState.cancelFutureGoalRule(ruleId: String, notBefore: LocalDate): AttentionState {
    val existing = futureGoalRules.firstOrNull { it.id == ruleId } ?: error("未来目标规则不存在")
    require(LocalDate.parse(existing.effectiveFrom).isAfter(notBefore)) {
        "已经生效的未来目标规则不能取消"
    }
    return copy(futureGoalRules = futureGoalRules.filterNot { it.id == ruleId })
}

/**
 * 修改一个已生效目标阶段的目标分钟数与截止日,经既有未来规则 overlay 落地。
 *
 * 生效点是该阶段周期粒度下、从 [notBefore](当前规划日)推算的下一个周期边界:
 * 当前周期整段仍由开始时的规则治理(周期快照的既述不变式),新承诺从下一周期
 * 第一天起整体生效,绝不在周期中途改写。overlay 的周期类型取阶段自身的类型,
 * 使生效边界与 overlay 治理的周期对齐;阶段本体字段保持原样,历史不改写。
 *
 * 同一阶段同一生效点的既有待生效 overlay 走更新语义(复用 [updateFutureGoalRule]
 * 的唯一性与生效界线校验),不重复堆叠;不同生效点的 overlay 各自独立。
 * 已完成(completed)阶段拒绝修改;一次性阶段的「周期」就是阶段本身,没有下一
 * 周期边界,overlay 只能把生效起点推到阶段中途、重写当前周期的进度窗口,因此
 * 结构性拒绝。生效日约束、归档状态等校验由 [addFutureGoalRule]/[updateFutureGoalRule]
 * 内部给出,此处不复制。
 */
fun AttentionState.updateGoalStage(
    stageId: String,
    targetMinutes: Int,
    dueDate: String?,
    notBefore: LocalDate,
): AttentionState {
    val stage = goalStages.firstOrNull { it.id == stageId } ?: error("目标阶段不存在")
    require(!stage.completed) { "已完成的目标阶段不能修改" }
    require(targetMinutes > 0) { "目标分钟必须为正整数" }
    require(stage.cadence != GoalCadence.ONE_TIME) { "一次性阶段没有下一周期边界,不能修改" }
    val effectiveFrom = nextPeriodBoundary(notBefore, stage.cadence).toString()
    val existing = futureGoalRules.firstOrNull {
        it.targetId == stage.targetId && it.stageId.trim().ifBlank { "" } == stage.id && it.effectiveFrom == effectiveFrom
    }
    val overlay = FutureGoalRule(
        id = existing?.id ?: newId(),
        targetId = stage.targetId,
        stageId = stage.id,
        cadence = stage.cadence,
        targetMinutes = targetMinutes,
        effectiveFrom = effectiveFrom,
        dueDate = dueDate,
    )
    return if (existing == null) {
        addFutureGoalRule(overlay, notBefore)
    } else {
        updateFutureGoalRule(overlay, notBefore)
    }
}

/**
 * 从 [day] 起按周期粒度定位下一个周期边界:次日本身开启新周期时即次日,
 * 否则当前周期的结束次日。周期口径与 [periodRange] 同源(含周起始日设置)。
 */
private fun AttentionState.nextPeriodBoundary(day: LocalDate, cadence: GoalCadence): LocalDate {
    val range = periodRange(day.plusDays(1), cadence)
    return if (range.start.isAfter(day)) range.start else range.endInclusive.plusDays(1)
}

fun AttentionState.goalRulesAt(targetId: String, onDate: LocalDate): List<FutureGoalRule> = futureGoalRules
    .filter { it.targetId == targetId && !LocalDate.parse(it.effectiveFrom).isAfter(onDate) }
    .sortedWith(compareBy<FutureGoalRule> { LocalDate.parse(it.effectiveFrom) }.thenBy { it.id })

fun FutureGoalRule.isPending(onDate: LocalDate): Boolean =
    runCatching { LocalDate.parse(effectiveFrom).isAfter(onDate) }.getOrDefault(false)

fun AttentionState.goalRuleAt(targetId: String, stageId: String?, onDate: LocalDate): FutureGoalRule? = futureGoalRules
    .asSequence()
    .filter { it.targetId == targetId && (it.stageId.isBlank() || it.stageId == stageId) }
    .filter { !LocalDate.parse(it.effectiveFrom).isAfter(onDate) }
    .maxWithOrNull(
        compareBy<FutureGoalRule> { LocalDate.parse(it.effectiveFrom) }
            .thenBy { it.stageId.isNotBlank() }
            .thenBy { it.id },
    )

private fun FutureGoalRule.sameScopeAs(other: FutureGoalRule): Boolean =
    targetId == other.targetId && stageId.trim().ifBlank { "" } == other.stageId.trim().ifBlank { "" }

private fun AttentionState.validateFutureGoalRule(rule: FutureGoalRule, notBefore: LocalDate) {
    require(targets.any { it.id == rule.targetId && !it.archived }) { "未来目标规则只能用于未归档目标" }
    require(rule.stageId.isBlank() || goalStages.any { it.id == rule.stageId && it.targetId == rule.targetId }) {
        "未来目标规则的阶段不存在或不属于目标"
    }
    require(rule.targetMinutes > 0) { "目标分钟必须为正整数" }
    val effectiveFrom = runCatching { LocalDate.parse(rule.effectiveFrom) }
        .getOrElse { throw IllegalArgumentException("生效规划日无效", it) }
    require(effectiveFrom.isAfter(notBefore)) {
        "未来目标规则必须从未来规划日生效"
    }
    val dueDate = rule.dueDate?.let {
        runCatching { LocalDate.parse(it) }
            .getOrElse { error -> throw IllegalArgumentException("截止规划日无效", error) }
    }
    require(dueDate == null || !dueDate.isBefore(effectiveFrom)) {
        "截止规划日不能早于生效规划日"
    }
}

private fun PeriodSnapshot.samePeriodAs(other: PeriodSnapshot): Boolean {
    val sameStage = stageId == other.stageId || stageId.isBlank() || other.stageId.isBlank()
    return sameStage && targetId == other.targetId && cadence == other.cadence &&
        periodStart == other.periodStart && periodEnd == other.periodEnd
}

fun AttentionState.addPeriodSnapshot(snapshot: PeriodSnapshot): AttentionState {
    if (periodSnapshots.any { it.samePeriodAs(snapshot) }) return this
    require(!LocalDate.parse(snapshot.periodEnd).isBefore(LocalDate.parse(snapshot.periodStart))) {
        "周期结束日不能早于开始日"
    }
    return copy(periodSnapshots = periodSnapshots + snapshot)
}

fun AttentionState.snapshot(stage: GoalStage, periodStart: LocalDate, periodEnd: LocalDate): PeriodSnapshot {
    require(!periodEnd.isBefore(periodStart)) { "周期结束日不能早于开始日" }
    // A cycle is governed by the rule that was active when that cycle began.
    // This keeps a future rule from rewriting an already-started weekly or
    // monthly cycle when it becomes effective in the middle of that cycle.
    val effective = effectiveStage(stage, periodStart)
    val effectiveStart = LocalDate.parse(effective.startDate)
    require(!periodEnd.isBefore(effectiveStart)) { "目标阶段尚未开始" }
    val actualStart = maxOf(periodStart, effectiveStart)
    val actual = timeEntries
        .filter { LocalDate.parse(it.planningDate) in actualStart..periodEnd }
        .filter { it.targetId != null }
        .filter { isDescendantAt(it.targetId!!, effective.targetId, LocalDate.parse(it.planningDate)) }
        .sumOf { it.durationMinutes }
    val days = (periodEnd.toEpochDay() - actualStart.toEpochDay() + 1).toInt()
    val targetMinutes = if (effective.cadence == GoalCadence.DAILY) effective.targetMinutes * days else effective.targetMinutes
    val gap = (targetMinutes - actual).coerceAtLeast(0)
    return PeriodSnapshot(
        targetId = stage.targetId,
        cadence = effective.cadence,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        targetMinutes = targetMinutes,
        actualMinutes = actual,
        gapMinutes = gap,
        excessMinutes = (actual - targetMinutes).coerceAtLeast(0),
        completed = actual >= targetMinutes,
        stageId = stage.id,
    )
}

fun AttentionState.settlePeriodSnapshots(asOf: LocalDate): AttentionState =
    goalStages.fold(this) { state, stage -> state.settleStageSnapshots(stage, asOf) }

private fun AttentionState.settleStageSnapshots(stage: GoalStage, asOf: LocalDate): AttentionState {
    var state = this
    var cursor = LocalDate.parse(stage.startDate)
    while (true) {
        val effective = state.effectiveStage(stage, cursor)
        val calendarRange = state.periodRange(cursor, effective.cadence)
        val periodStart = maxOf(cursor, calendarRange.start)
        val periodEnd = when (effective.cadence) {
            GoalCadence.ONE_TIME -> effective.dueDate?.let(LocalDate::parse) ?: periodStart
            else -> calendarRange.endInclusive
        }
        if (!periodEnd.isBefore(asOf)) break
        state = state.addPeriodSnapshot(state.snapshot(stage, periodStart, periodEnd))
        if (effective.cadence == GoalCadence.ONE_TIME) break
        cursor = periodEnd.plusDays(1)
    }
    return state
}

fun AttentionState.addFutureTargetMove(
    targetId: String,
    parentId: String?,
    effectiveFrom: String,
    notBefore: LocalDate,
): AttentionState {
    val move = TargetMove(targetId = targetId, parentId = parentId, effectiveFrom = effectiveFrom)
    validateFutureTargetMove(move, notBefore)
    return copy(targetMoves = targetMoves + move)
}

fun parseTargetMoveDate(effectiveFrom: String): LocalDate? = runCatching { LocalDate.parse(effectiveFrom) }.getOrNull()

fun isFutureTargetMoveDate(effectiveFrom: String, notBefore: LocalDate): Boolean =
    parseTargetMoveDate(effectiveFrom)?.isAfter(notBefore) == true

fun AttentionState.pendingTargetMoves(targetId: String, onDate: LocalDate): List<TargetMove> = targetMoves
    .asSequence()
    .filter { it.targetId == targetId }
    .filter { isFutureTargetMoveDate(it.effectiveFrom, onDate) }
    .sortedBy { it.effectiveFrom }
    .toList()

fun AttentionState.futureTargetMoveParents(
    targetId: String,
    effectiveFrom: String,
    notBefore: LocalDate,
): List<Target> {
    val effectiveDate = parseTargetMoveDate(effectiveFrom) ?: return emptyList()
    if (!effectiveDate.isAfter(notBefore)) return emptyList()
    val excludedIds = descendantIds(targetId) + descendantIdsAt(targetId, effectiveDate)
    return targets.filter { !it.archived && it.id !in excludedIds }
}

fun AttentionState.updateFutureTargetMove(move: TargetMove, notBefore: LocalDate): AttentionState {
    val existing = targetMoves.firstOrNull { it.id == move.id } ?: error("未来目标移动不存在")
    require(existing.targetId == move.targetId) { "不能修改未来目标移动的目标" }
    require(LocalDate.parse(existing.effectiveFrom).isAfter(notBefore)) { "只能修改尚未生效的目标移动" }
    validateFutureTargetMove(move, notBefore, replacingId = move.id)
    return copy(targetMoves = targetMoves.map { if (it.id == move.id) move else it })
}

fun AttentionState.cancelFutureTargetMove(moveId: String, notBefore: LocalDate): AttentionState {
    val existing = targetMoves.firstOrNull { it.id == moveId } ?: error("未来目标移动不存在")
    val effectiveFrom = LocalDate.parse(existing.effectiveFrom)
    require(effectiveFrom.isAfter(notBefore)) { "只能取消尚未生效的目标移动" }
    return copy(targetMoves = targetMoves.filterNot { it.id == moveId })
}

private fun AttentionState.validateFutureTargetMove(
    move: TargetMove,
    notBefore: LocalDate,
    replacingId: String? = null,
) {
    require(targets.any { it.id == move.targetId }) { "目标不存在" }
    require(move.parentId == null || targets.any { it.id == move.parentId && !it.archived }) {
        "新的父目标不存在或已归档"
    }
    require(move.parentId == null || move.parentId !in descendantIds(move.targetId)) {
        "不能移动到自身或后代目标"
    }
    val effectiveFrom = runCatching { LocalDate.parse(move.effectiveFrom) }
        .getOrElse { throw IllegalArgumentException("生效日无效", it) }
    require(effectiveFrom.isAfter(notBefore)) { "生效日必须晚于当前规划日" }
    require(targetMoves.none {
        it.id != replacingId && it.targetId == move.targetId && it.effectiveFrom == move.effectiveFrom
    }) { "同一目标在同一生效日只能有一条移动记录" }

    val movesWithCandidate = targetMoves.filterNot { it.id == replacingId } + move
    val validationDates = movesWithCandidate
        .map { LocalDate.parse(it.effectiveFrom) }
        .filterNot { it.isBefore(effectiveFrom) }
        .distinct()
    validationDates.forEach { validationDate ->
        var current = move.parentId
        val seen = mutableSetOf<String>()
        while (current != null) {
            require(current != move.targetId) { "目标移动会形成循环" }
            require(seen.add(current)) { "目标移动会形成循环" }
            current = parentAt(current, validationDate, movesWithCandidate)
        }
    }
}

private fun AttentionState.parentAt(targetId: String, date: LocalDate, moves: List<TargetMove>): String? {
    val applicable = moves
        .asSequence()
        .filter { it.targetId == targetId && !LocalDate.parse(it.effectiveFrom).isAfter(date) }
        .maxWithOrNull(compareBy<TargetMove> { LocalDate.parse(it.effectiveFrom) }.thenBy { it.id })
    return if (applicable != null) applicable.parentId else targets.firstOrNull { it.id == targetId }?.parentId
}

fun AttentionState.parentAt(targetId: String, date: LocalDate): String? = parentAt(targetId, date, targetMoves)

fun AttentionState.materializeOccurrences(
    rule: RecurrenceRule,
    through: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): AttentionState {
    // Resolve the persisted rule first. This keeps generation safe when a caller
    // retries with a stale copy (for example after stopping a rule).
    val persistedRule = recurrenceRules.firstOrNull { it.id == rule.id } ?: rule
    val generated = occurrences(persistedRule, through, zone)
    val existing = schedules.map { it.recurrenceRuleId to it.planningDate }.toSet()
    val additions = generated.filterNot { (it.recurrenceRuleId to it.planningDate) in existing }
        .map { it.copy(id = newId()) }
    return copy(schedules = schedules + additions)
}

/** Creates a rule and materializes its first window as one atomic domain command. */
fun AttentionState.addRecurrenceAndMaterialize(
    rule: RecurrenceRule,
    through: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): AttentionState = addRecurrence(rule).materializeOccurrences(rule, through, zone)

fun AttentionState.startTimer(
    targetId: String?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    scheduleEntryId: String? = null,
): AttentionState {
    require(validRecordTarget(targetId)) { "计时只能归入未归档目标" }
    require(scheduleEntryId == null || schedules.any { it.id == scheduleEntryId }) { "计时只能关联存在的日程条目" }
    require(activeTimer == null) { "已有活动计时器，请先暂停或结束" }
    return copy(activeTimer = ActiveTimer(targetId = targetId, startedAtEpochMillis = now.toEpochMilli(), lastPlanningDate = planningDate(now, zone).toString(), scheduleEntryId = scheduleEntryId))
}

fun AttentionState.switchTimer(targetId: String?, now: Instant, zone: ZoneId = ZoneId.systemDefault(), scheduleEntryId: String? = null): TimerResult {
    require(validRecordTarget(targetId)) { "计时只能归入未归档目标" }
    if (activeTimer == null) return TimerResult(startTimer(targetId, now, zone, scheduleEntryId), emptyList())
    if (activeTimer.targetId == targetId) return TimerResult(this, emptyList())
    val stopped = stopTimer(now, zone)
    return TimerResult(stopped.state.startTimer(targetId, now, zone, scheduleEntryId), stopped.entries)
}

fun AttentionState.pauseTimer(now: Instant): AttentionState {
    val timer = activeTimer ?: return this
    if (timer.paused) return this
    val additional = (now.toEpochMilli() - timer.startedAtEpochMillis).coerceAtLeast(0)
    val endedAt = maxOf(timer.startedAtEpochMillis, now.toEpochMilli())
    return copy(activeTimer = timer.copy(
        accumulatedMillis = timer.accumulatedMillis + additional,
        paused = true,
        completedSegments = timer.completedSegments + TimerSegment(timer.startedAtEpochMillis, endedAt),
    ))
}

fun AttentionState.resumeTimer(now: Instant): AttentionState {
    val timer = activeTimer ?: return this
    if (!timer.paused) return this
    return copy(activeTimer = timer.copy(startedAtEpochMillis = timer.nonDecreasingBaseline(now.toEpochMilli()), paused = false))
}

fun AttentionState.recoverTimer(now: Instant): AttentionState {
    val timer = activeTimer ?: return this
    return copy(activeTimer = timer.copy(startedAtEpochMillis = timer.nonDecreasingBaseline(now.toEpochMilli()), paused = false))
}

private fun ActiveTimer.nonDecreasingBaseline(nowEpochMillis: Long): Long = maxOf(
    nowEpochMillis,
    startedAtEpochMillis,
    completedSegments.maxOfOrNull { it.endedAtEpochMillis } ?: startedAtEpochMillis,
)

fun AttentionState.stopTimer(now: Instant, zone: ZoneId = ZoneId.systemDefault()): TimerResult {
    val timer = activeTimer ?: return TimerResult(this, emptyList())
    val segments = timer.completedSegments.toMutableList()
    if (!timer.paused) {
        val endedAt = maxOf(timer.startedAtEpochMillis, now.toEpochMilli())
        segments += TimerSegment(timer.startedAtEpochMillis, endedAt)
    }
    val entries = splitTimerSegments(segments, timer.targetId, timer.scheduleEntryId, settings.planningDayBoundaryMinutes, zone)
    return TimerResult(copy(activeTimer = null, timeEntries = timeEntries + entries).recalculateExperience(), entries)
}

/** Stops the active timer and evaluates milestone eligibility at the stop instant. */
fun AttentionState.stopTimerAndAwardEligibleMilestones(
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): TimerResult {
    val stopped = stopTimer(now, zone)
    return stopped.copy(state = stopped.state.awardEligibleMilestones(stopped.state.planningDate(now, zone)))
}

/**
 * Calculates the records an explicit stop would produce without changing state.
 * Callers supply the confirmation time so the preview remains deterministic.
 */
fun AttentionState.timerSettlementPreview(
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): TimerSettlementPreview = TimerSettlementPreview(activeTimer?.let { stopTimer(now, zone).entries } ?: emptyList())

private fun splitTimerSegments(segments: List<TimerSegment>, targetId: String?, scheduleEntryId: String?, boundaryMinutes: Int, zone: ZoneId): List<TimeEntry> {
    data class TimerBucket(var durationMillis: Long, val occurredAtEpochMillis: Long)

    val buckets = linkedMapOf<LocalDate, TimerBucket>()
    segments.sortedBy { it.startedAtEpochMillis }.forEach { segment ->
        var cursor = Instant.ofEpochMilli(segment.startedAtEpochMillis)
        val end = Instant.ofEpochMilli(maxOf(segment.startedAtEpochMillis, segment.endedAtEpochMillis))
        while (cursor.isBefore(end)) {
            val local = cursor.atZone(zone)
            val planningDate = local.toLocalDate().let {
                if (local.toLocalTime().toSecondOfDay() / 60 < boundaryMinutes) it.minusDays(1) else it
            }
            val nextBoundary = planningDate.plusDays(1).atStartOfDay(zone).plusMinutes(boundaryMinutes.toLong()).toInstant()
            val chunkEnd = minOf(end, nextBoundary)
            val duration = chunkEnd.toEpochMilli() - cursor.toEpochMilli()
            val bucket = buckets.getOrPut(planningDate) { TimerBucket(0L, cursor.toEpochMilli()) }
            bucket.durationMillis += duration
            cursor = chunkEnd
        }
    }
    return buckets.mapNotNull { (date, bucket) ->
        // A partial minute cannot be carried across planning-day boundaries:
        // each date is an independent ledger bucket.
        val minutes = (bucket.durationMillis / 60_000L).toInt()
        if (minutes == 0) null else TimeEntry(
            planningDate = date.toString(),
            durationMinutes = minutes,
            targetId = targetId,
            source = TimeEntrySource.TIMER,
            occurredAtEpochMillis = bucket.occurredAtEpochMillis,
            scheduleEntryId = scheduleEntryId,
        )
    }
}

fun AttentionState.recalculateExperience(): AttentionState = copy(
    experience = timeEntries.filter { it.targetId != null }.sumOf { it.durationMinutes.toLong() } +
        milestones.distinctBy { it.instanceKey }.sumOf { it.reward },
)

fun AttentionState.continuousPlanningDays(targetId: String? = null): Int {
    val dates = timeEntries.asSequence()
        .filter { it.targetId != null && (targetId == null || it.targetId in descendantIds(targetId)) }
        .map { LocalDate.parse(it.planningDate) }
        .toSet()
    if (dates.isEmpty()) return 0
    var best = 0
    var run = 0
    var previous: LocalDate? = null
    dates.sorted().forEach { date ->
        run = if (previous?.plusDays(1) == date) run + 1 else 1
        best = maxOf(best, run)
        previous = date
    }
    return best
}

fun AttentionState.continuousPlanningDaysThrough(onDate: LocalDate, targetId: String? = null): Int {
    val dates = timeEntries.asSequence()
        .mapNotNull { entry ->
            val entryDate = LocalDate.parse(entry.planningDate)
            if (entry.targetId == null || entryDate > onDate) return@mapNotNull null
            if (targetId != null && !isDescendantAt(entry.targetId, targetId, entryDate)) return@mapNotNull null
            entryDate
        }
        .toSet()
    var cursor = onDate
    var count = 0
    while (cursor in dates) {
        count++
        cursor = cursor.minusDays(1)
    }
    return count
}

private const val CUMULATIVE_MILESTONE_KIND = "cumulative"

/**
 * 累计投入里程碑的 instanceKey 形如 cumulative:小时数。kind 或 key 格式不符时返回 null，
 * 调用方据此展示，而不是自己解析 key —— key 格式是 :domain 的规则。
 */
fun Milestone.cumulativeHours(): Long? =
    if (kind == CUMULATIVE_MILESTONE_KIND) instanceKey.substringAfter("$CUMULATIVE_MILESTONE_KIND:").toLongOrNull() else null

/** 累计投入里程碑：按达成时间排序，同一 instanceKey 只保留一条。 */
fun AttentionState.cumulativeMilestones(): List<Milestone> = milestones
    .filter { it.kind == CUMULATIVE_MILESTONE_KIND }
    .sortedWith(compareBy({ it.achievedAtEpochMillis }, { it.id }))
    .distinctBy { it.instanceKey }

private const val ONE_TIME_GOAL_MILESTONE_KIND = "one_time_goal"

fun oneTimeGoalMilestoneKey(stageId: String, startDate: String): String =
    "$ONE_TIME_GOAL_MILESTONE_KIND:$stageId:$startDate"

fun Milestone.isOneTimeStageFeedbackFor(stageId: String): Boolean {
    if (kind != ONE_TIME_GOAL_MILESTONE_KIND) return false
    val payload = instanceKey.removePrefix("$ONE_TIME_GOAL_MILESTONE_KIND:")
    return payload.substringBeforeLast(":", missingDelimiterValue = "") == stageId
}

fun AttentionState.oneTimeStageMilestones(stageId: String): List<Milestone> = milestones
    .filter { it.isOneTimeStageFeedbackFor(stageId) }
    .sortedWith(compareBy<Milestone> { it.achievedAtEpochMillis }.thenBy { it.id })

fun AttentionState.recentMilestones(limit: Int = 8): List<Milestone> {
    require(limit >= 0) { "里程碑数量不能为负数" }
    return milestones
        .sortedWith(compareBy<Milestone> { it.achievedAtEpochMillis }.thenBy { it.id })
        .distinctBy { it.instanceKey }
        .takeLast(limit)
}

private fun MutableList<Milestone>.addMilestoneIfMissing(
    existingKeys: MutableSet<String>,
    kind: String,
    instanceKey: String,
    reward: Long,
    achievedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    if (existingKeys.add(instanceKey)) {
        add(Milestone(kind = kind, instanceKey = instanceKey, reward = reward, achievedAtEpochMillis = achievedAtEpochMillis))
    }
}

/**
 * Builds all milestone candidates that are eligible through [onDate].
 *
 * The timestamp is explicit for command paths. The public repair helper below keeps
 * its historical system-clock behaviour for existing callers.
 */
internal fun AttentionState.eligibleMilestonesAt(
    onDate: LocalDate,
    achievedAtEpochMillis: Long,
): List<Milestone> {
    val existing = milestones.map { it.instanceKey }.toMutableSet()
    val result = mutableListOf<Milestone>()
    goalStages.forEach { stage ->
        val progress = progress(stage, onDate)
        if (progress.completed) {
            val effective = effectiveStage(stage, onDate)
            val kind = if (effective.cadence == GoalCadence.ONE_TIME) ONE_TIME_GOAL_MILESTONE_KIND else "goal_period"
            val periodStart = if (effective.cadence == GoalCadence.ONE_TIME) effective.startDate else periodRange(onDate, effective.cadence).start.toString()
            val key = if (effective.cadence == GoalCadence.ONE_TIME) {
                oneTimeGoalMilestoneKey(stage.id, periodStart)
            } else {
                "$kind:${stage.id}:$periodStart"
            }
            val reward = when (effective.cadence) {
                GoalCadence.ONE_TIME -> 10_000L
                GoalCadence.DAILY -> 500L
                GoalCadence.WEEKLY, GoalCadence.MONTHLY -> 1_000L
            }
            result.addMilestoneIfMissing(existing, kind, key, reward, achievedAtEpochMillis)
        }
    }
    targets.filter { !it.archived }.forEach { target ->
        val days = continuousPlanningDaysThrough(onDate, target.id)
        listOf(7 to 500L, 30 to 1_000L, 100 to 10_000L).forEach { (threshold, reward) ->
            val key = "streak:${target.id}:$threshold"
            if (days >= threshold) result.addMilestoneIfMissing(existing, "streak", key, reward, achievedAtEpochMillis)
        }
    }
    val totalHours = timeEntries
        .filter { it.targetId != null && LocalDate.parse(it.planningDate) <= onDate }
        .sumOf { it.durationMinutes.toLong() } / 60
    (100L..totalHours step 100L).forEach { hours ->
        val key = "$CUMULATIVE_MILESTONE_KIND:$hours"
        val reward = cumulativeMilestoneReward(hours)
        if (reward != null) result.addMilestoneIfMissing(existing, CUMULATIVE_MILESTONE_KIND, key, reward, achievedAtEpochMillis)
    }
    return result
}

fun AttentionState.eligibleMilestones(onDate: LocalDate): List<Milestone> =
    eligibleMilestonesAt(onDate, System.currentTimeMillis())

fun AttentionState.awardEligibleMilestones(onDate: LocalDate): AttentionState {
    val additions = eligibleMilestones(onDate)
    return copy(milestones = milestones + additions).recalculateExperience()
}

/** Returns the cumulative experience required to enter [level]. */
fun levelThreshold(level: Int): Long {
    require(level >= 1) { "等级必须从 1 开始" }
    if (level == 1) return 0
    val n = level.toLong()
    val multiplier = 500L * n
    return if (multiplier > Long.MAX_VALUE / (n - 1)) Long.MAX_VALUE else multiplier * (n - 1)
}

fun levelForExperience(experience: Long): Int {
    val safeExperience = experience.coerceAtLeast(0)
    var low = 1
    var high = highestFiniteLevel()
    while (low < high) {
        val middle = low + (high - low + 1) / 2
        if (levelThreshold(middle) <= safeExperience) {
            low = middle
        } else {
            high = middle - 1
        }
    }
    return low
}

private fun highestFiniteLevel(): Int {
    var low = 1
    var high = Int.MAX_VALUE
    while (low < high) {
        val middle = low + (high - low + 1) / 2
        if (levelThreshold(middle) < Long.MAX_VALUE) {
            low = middle
        } else {
            high = middle - 1
        }
    }
    return low
}

fun levelProgressForExperience(experience: Long): LevelProgress {
    val safeExperience = experience.coerceAtLeast(0)
    val level = levelForExperience(safeExperience)
    val currentThreshold = levelThreshold(level)
    val nextThreshold = levelThreshold(level + 1)
    return LevelProgress(
        level = level,
        currentLevelThreshold = currentThreshold,
        nextLevelThreshold = nextThreshold,
        progressExperience = (safeExperience - currentThreshold).coerceAtLeast(0),
        remainingExperience = (nextThreshold - safeExperience).coerceAtLeast(0),
    )
}

fun cumulativeMilestoneReward(hours: Long): Long? {
    if (hours < 100 || hours % 100 != 0L) return null
    val band = (hours / 500) + 1
    return 10_000L * band
}

fun AttentionState.unownedMinutes(): Int = timeEntries.filter { it.targetId == null }.sumOf { it.durationMinutes }

fun AttentionState.activeTargetMinutes(date: String): Int = timeEntries.filter { it.planningDate == date && it.targetId != null }.sumOf { it.durationMinutes }
