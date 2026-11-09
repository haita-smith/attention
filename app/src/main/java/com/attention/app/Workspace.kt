package com.attention.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import java.io.OutputStreamWriter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attention.app.agent.rememberWorkspaceAgentRuntime
import com.attention.app.data.AttentionStateRepository
import com.attention.app.data.RoomAttentionStateRepository
import com.attention.app.data.backup.BackupCommitResult
import com.attention.app.data.backup.ExportBundle
import com.attention.app.data.backup.ExportDocument
import com.attention.app.data.backup.ImportMode
import com.attention.app.data.backup.ImportPlan
import com.attention.app.data.backup.FileImportBackupStore
import com.attention.app.data.room.AttentionDatabase
import com.attention.app.data.room.RoomBusinessDataRepository
import com.attention.app.data.settings.createSettingsStore
import com.attention.app.timer.AndroidTimerEffects
import com.attention.app.timer.TimerEffects
import com.attention.app.reminder.AlarmReminderCoordinator
import com.attention.app.reminder.ReminderScheduler
import com.attention.app.ui.components.AttentionButton
import com.attention.app.ui.components.AttentionInlineError
import com.attention.app.ui.components.AttentionOutlinedButton
import com.attention.app.ui.components.AttentionSnackbarHost
import com.attention.app.ui.components.AttentionTextButton
import com.attention.app.ui.components.rememberAttentionFeedbackHostState
import com.attention.app.ui.theme.attentionTokens
import com.attention.app.agenda.AgendaScreen
import com.attention.app.agenda.AgendaSelection
import com.attention.app.agenda.rememberAgendaSelection
import com.attention.app.onboarding.OnboardingScreen
import com.attention.app.settings.SettingsScreen
import com.attention.app.statistics.StatisticsScreen
import com.attention.app.statistics.StatisticsSelection
import com.attention.app.statistics.rememberStatisticsSelection
import com.attention.app.targets.TargetsScreen
import com.attention.app.targets.rememberTargetsSelection
import com.attention.workspace.state.AgendaActions
import com.attention.workspace.state.AgendaOperationFeedback
import com.attention.workspace.state.AgendaBackfillDraft
import com.attention.workspace.state.AgendaRecurrenceDraft
import com.attention.workspace.state.AgendaScheduleDraft
import com.attention.workspace.state.AGENDA_UNDO_LABEL
import com.attention.workspace.state.OnboardingActions
import com.attention.workspace.state.SettingsActions
import com.attention.workspace.state.StatisticsActions
import com.attention.workspace.state.TargetsActions
import com.attention.workspace.state.agendaFeedbackMessage
import com.attention.workspace.state.agendaScreenState
import com.attention.workspace.state.onboardingScreenState
import com.attention.workspace.state.settingsScreenState
import com.attention.workspace.state.statisticsScreenState
import com.attention.workspace.state.targetsScreenState
import com.attention.workspace.state.toRecurrenceRule
import com.attention.domain.AttentionState
import com.attention.domain.CapacitySummary
import com.attention.domain.GoalCadence
import com.attention.domain.FutureGoalRule
import com.attention.domain.LaunchDestination
import com.attention.domain.Migration
import com.attention.domain.newId
import com.attention.domain.ScheduleEntry
import com.attention.domain.ScheduleBackfill
import com.attention.domain.StoredSettings
import com.attention.domain.TimeEntry
import com.attention.domain.asAgendaEntry
import com.attention.domain.capacitySummary
import com.attention.domain.externalSchedulePlanningDate
import com.attention.domain.planningDate
import com.attention.domain.reminderEpochMillis
import com.attention.domain.resolveInitialLaunchDestination
import com.attention.domain.resolveLaunchDestination
import com.attention.domain.toTopLevelWorkspace
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 组合根交给日程页的两个加载事实。
 *
 * 「还在读」与「读失败」都由组合根判定（ADR-0005 第 1 节）：页面拿到的领域状态在读上来之前
 * 是一份空的默认值，它自己分不出「用户真的没有数据」与「还没读到」，因此页面不去猜。
 * 读取失败优先于加载中：失败时页面给的是带「重试」的错误状态，而不是一直转骨架。
 */
internal data class AgendaLoadFacts(
    val loading: Boolean,
    val loadError: String?,
)

internal fun agendaLoadFacts(stateLoaded: Boolean, loadError: String?): AgendaLoadFacts = AgendaLoadFacts(
    loading = !stateLoaded && loadError == null,
    loadError = loadError,
)

/**
 * 设置页的 actions adapter，在组合根一侧组装：页面发出 intent，这里落到 ViewModel、
 * 通知权限申请，以及导入导出与恢复所依赖的 SAF 文件选择器和确认对话框。
 * ViewModel、Room、DataStore 与平台能力因此只出现在组合根侧，页面拿不到它们（ADR-0005 第 1、4 节）。
 */
private class SettingsActionsAdapter(
    private val planningActions: SettingsPlanningActions,
    private val currentWeekStartDay: () -> Int,
    private val onRequestReminderPermission: () -> Unit,
    private val onPreviewJsonBackup: () -> Unit,
    private val onImportJson: () -> Unit,
    private val onExportTimeEntriesCsv: () -> Unit,
    private val onPreviewCsv: () -> Unit,
    private val onExportSchedulesCsv: () -> Unit,
    private val onExportCsvBundle: () -> Unit,
    private val onRequestRestoreImportBackup: () -> Unit,
    private val onOpenOnboarding: () -> Unit,
    private val onClose: () -> Unit,
) : SettingsActions {
    override fun selectLaunchDestination(destination: LaunchDestination) {
        planningActions.setLaunchDestination(destination)
    }

    // 界线与容量一起写进同一份规划设置；周起始日当前的值由组合根读出来一起提交。
    override fun savePlanningDaySettings(boundaryMinutes: Int, dailyCapacityMinutes: Int?) {
        planningActions.setSettings(StoredSettings(boundaryMinutes, currentWeekStartDay(), dailyCapacityMinutes))
    }

    override fun selectWeekStartDay(day: Int) {
        planningActions.setWeekStart(day)
    }

    override fun requestNotificationPermission() = onRequestReminderPermission()

    override fun previewJsonBackup() = onPreviewJsonBackup()

    override fun importJson() = onImportJson()

    override fun exportTimeEntriesCsv() = onExportTimeEntriesCsv()

    override fun previewCsv() = onPreviewCsv()

    override fun exportSchedulesCsv() = onExportSchedulesCsv()

    override fun exportCsvBundle() = onExportCsvBundle()

    // 恢复导入前备份需要用户显式确认：页面只发出 intent，由组合根打开确认对话框，
    // 真正的恢复由对话框确认后调用 ViewModel 的结果化命令完成。workflow 与迁移前一致。
    override fun restoreLastImportBackup() = onRequestRestoreImportBackup()

    override fun close() = onClose()

    // 重新打开首次设置：组合根切到独立设置页，页面只发出这一条意图。
    override fun openOnboarding() = onOpenOnboarding()
}

/**
 * 首次使用引导的 actions adapter，在组合根一侧组装。
 *
 * 每个写入动作都只在页面确认对应步骤后落地；跳过当前步骤只改变页面索引，
 * `completeOnboarding()` 再更新引导状态并把用户送回当前规划日的日程页。
 */
private class OnboardingActionsAdapter(
    private val planningActions: OnboardingPlanningActions,
    private val onCompleted: () -> Unit,
) : OnboardingActions {
    override fun savePlanningDaySettings(boundaryMinutes: Int, weekStartDay: Int, dailyCapacityMinutes: Int?) {
        planningActions.setSettings(
            StoredSettings(
                planningDayBoundaryMinutes = boundaryMinutes,
                weekStartDay = weekStartDay,
                dailyCapacityMinutes = dailyCapacityMinutes,
            ),
        )
    }

    override fun selectWeekStartDay(day: Int) {
        planningActions.setWeekStart(day)
    }

    override fun addFirstTarget(title: String) {
        planningActions.addTarget(title)
    }

    override fun addFirstSchedule(draft: AgendaScheduleDraft) {
        planningActions.saveSchedule(
            ScheduleEntry(
                planningDate = draft.planningDate,
                title = draft.title,
                startMinute = draft.startMinute,
                endMinute = draft.endMinute,
                estimatedMinutes = draft.estimatedMinutes,
                note = draft.note,
                targetId = draft.targetId,
                reminderEpochMillis = null,
            ),
        )
    }

    override fun completeOnboarding() {
        planningActions.finishOnboarding()
        onCompleted()
    }
}

/**
 * 日程页的 actions adapter，在组合根一侧组装：页面发出 intent，这里落到 ViewModel、
 * 提醒的绝对时刻换算与通知权限申请。ViewModel、`Activity`/`Context` 与平台能力
 * 因此只出现在组合根侧（ADR-0005 第 4 节「Android adapter 归属组合根」）。
 */
private class AgendaActionsAdapter(
    private val investmentActions: InvestmentActions,
    private val planningActions: AgendaPlanningActions,
    private val selection: AgendaSelection,
    private val currentDate: () -> LocalDate,
    private val currentPlanningDay: () -> LocalDate,
    private val zone: ZoneId,
    private val onStartTimerFromEntry: (ScheduleEntry) -> Unit,
    private val onStartFreeTimer: (String?) -> Unit,
    private val onRequestReminderPermission: () -> Unit,
    private val onStopTimer: () -> Unit,
    private val onPauseTimer: () -> Unit,
    private val onResumeTimer: () -> Unit,
    private val onRecoverTimerFromNow: () -> Unit,
    private val onSwitchTimer: (String?) -> Unit,
    private val onTimeRecordDeleted: (TimeEntry) -> Unit,
    private val onOpenSettings: () -> Unit,
    /**
     * 短时成功确认的出口。
     *
     * 成功提示属于组合根的反馈宿主（ADR-0005 第 1 节）：页面只发 intent，这里把它交给
     * 宿主展示，因此页面里没有 Toast，也没有第二套反馈方式。
     */
    private val onFeedback: (AgendaOperationFeedback) -> Unit,
) : AgendaActions {
    private fun scheduleEntryFrom(draft: AgendaScheduleDraft, date: LocalDate): ScheduleEntry = ScheduleEntry(
        planningDate = draft.planningDate,
        title = draft.title,
        startMinute = draft.startMinute,
        endMinute = draft.endMinute,
        estimatedMinutes = draft.estimatedMinutes,
        note = draft.note,
        targetId = draft.targetId,
        reminderEpochMillis = draft.reminderMinuteOfDay?.let { minute ->
            reminderEpochMillis(date, minute, zone)
        },
    )

    override fun selectPlanningDate(date: LocalDate?) {
        // 点击那一刻的规划日要一起交进去：范围高亮按它判定，之后跨过规划日界线也不会丢。
        selection.selectDate(date, currentPlanningDay())
    }

    override fun selectAllDates() {
        selection.selectAllDates()
    }

    override fun shiftPlanningDay(days: Long) {
        selection.shiftDays(days, currentDate(), currentPlanningDay())
    }

    override fun addSchedule(draft: AgendaScheduleDraft) {
        val date = LocalDate.parse(draft.planningDate)
        planningActions.saveSchedule(scheduleEntryFrom(draft, date))
        onFeedback(AgendaOperationFeedback.SCHEDULE_ADDED)
    }

    override fun addScheduleWithBackfill(draft: AgendaScheduleDraft, backfill: AgendaBackfillDraft) {
        val date = LocalDate.parse(draft.planningDate)
        planningActions.saveSchedule(
            scheduleEntryFrom(draft, date),
            ScheduleBackfill(
                durationMinutes = backfill.durationMinutes,
                targetId = backfill.targetId,
                note = backfill.note,
            ),
        )
        onFeedback(AgendaOperationFeedback.TIME_RECORDED)
    }

    override fun updateSchedule(entry: ScheduleEntry, reminderMinuteOfDay: Int?) {
        planningActions.saveSchedule(entry.copy(reminderEpochMillis = reminderMinuteOfDay?.let { minute ->
            reminderEpochMillis(LocalDate.parse(entry.planningDate), minute, zone)
        }))
        onFeedback(AgendaOperationFeedback.SCHEDULE_UPDATED)
    }

    override fun updateScheduleWithBackfill(
        entry: ScheduleEntry,
        reminderMinuteOfDay: Int?,
        backfill: AgendaBackfillDraft,
    ) {
        planningActions.saveSchedule(
            entry.copy(reminderEpochMillis = reminderMinuteOfDay?.let { minute ->
                reminderEpochMillis(LocalDate.parse(entry.planningDate), minute, zone)
            }),
            ScheduleBackfill(
                durationMinutes = backfill.durationMinutes,
                targetId = backfill.targetId,
                note = backfill.note,
                dedupeExistingSchedule = true,
            ),
        )
        onFeedback(AgendaOperationFeedback.TIME_RECORDED)
    }

    override fun confirmScheduleCompleted(entryId: String) {
        planningActions.confirmScheduleCompleted(entryId)
        onFeedback(AgendaOperationFeedback.SCHEDULE_COMPLETED)
    }

    override fun toggleScheduleCompleted(entryId: String) {
        planningActions.toggleScheduleCompleted(entryId)
        // 切换完成与取消完成走同一条命令，确认因此只说「完成状态已更新」，
        // 不去猜这一次到底是切到了哪一侧——结论由时间轴上的那一行自己说。
        onFeedback(AgendaOperationFeedback.SCHEDULE_COMPLETION_TOGGLED)
    }

    // 结果面板上的「标记为完成」与行上的完成是同一件事：走同一条既有完成路径，
    // 完成标记与投入经验、里程碑仍由领域计算，不新造命令。
    override fun markStoppedScheduleCompleted(entryId: String) {
        planningActions.markStoppedScheduleCompleted(entryId)
        planningActions.clearStoppedEntryIds()
    }

    // 「结束并保留未完成」只是关闭这一次结果面板：投入已经记下，完成状态保持原样。
    override fun dismissStopResult() {
        planningActions.clearStoppedEntryIds()
    }

    // 条目一起带出，因此产出的时间记录保留同一个来源关联。
    override fun startTimerFromEntry(entry: ScheduleEntry) {
        onStartTimerFromEntry(entry)
        onFeedback(AgendaOperationFeedback.TIMER_STARTED)
    }

    // 自由计时没有日程来源：scheduleEntryId 必须是 null，不能拿某条日程冒充来源。
    // 平台动作（前台计时服务、恢复提示、通知权限提示）与从条目起计时完全一致。
    override fun startFreeTimer(targetId: String?) = onStartFreeTimer(targetId)

    // 运行中的计时只能从日程页停掉：平台动作（停前台计时服务、撤掉恢复提示）仍归组合根。
    override fun stopRunningTimer() {
        onStopTimer()
        onFeedback(AgendaOperationFeedback.TIMER_STOPPED)
    }

    // 暂停、恢复、切换与从此刻继续都只是领域命令：通知栏是持久化状态的投影，
    // 因此这些动作不需要页面自己去碰前台服务（ADR-0005 第 4 节）。
    override fun pauseRunningTimer() {
        onPauseTimer()
    }

    override fun resumeRunningTimer() {
        onResumeTimer()
    }

    // 从此刻继续意味着用户已经对上一次被打断的计时做了决定，恢复提示要一并撤掉。
    override fun recoverRunningTimerFromNow() = onRecoverTimerFromNow()

    override fun switchRunningTimerTarget(targetId: String?) {
        onSwitchTimer(targetId)
    }

    override fun editTime(entryId: String, minutes: Int, targetId: String?, note: String) {
        investmentActions.editTime(entryId, minutes, targetId, note)
        onFeedback(AgendaOperationFeedback.TIME_RECORDED)
    }

    // 删除的可撤销提示由组合根展示：撤销要用回被删掉的那条记录本身。
    override fun deleteTime(entryId: String) {
        investmentActions.deleteTimeWithUndo(entryId, onTimeRecordDeleted)
    }

    override fun addRecurrenceAndMaterialize(draft: AgendaRecurrenceDraft) {
        val rule = draft.toRecurrenceRule()
        planningActions.addRecurrenceAndMaterialize(rule, LocalDate.parse(rule.startDate).plusDays(30))
        onFeedback(AgendaOperationFeedback.RECURRENCE_ADDED)
    }

    override fun stopRecurrence(ruleId: String) {
        planningActions.stopRecurrence(ruleId)
        onFeedback(AgendaOperationFeedback.RECURRENCE_STOPPED)
    }

    override fun requestReminderPermission() = onRequestReminderPermission()

    // 重试就是重新读一次本地数据：重新订阅发生在组合根持有的仓储上，页面不碰持久化。
    override fun retryLoad() {
        planningActions.retryLoad()
        onFeedback(AgendaOperationFeedback.LOAD_RETRYING)
    }

    // 完整设置住在统计页底部的「设置与数据」；工作台只发出这一条意图，跳转本身由组合根完成。
    override fun openSettings() = onOpenSettings()
}

private class WorkspaceViewModelFactory(
    private val repository: AttentionStateRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AttentionViewModel(repository) as T
}

/**
 * 统计页的 actions adapter，在组合根一侧组装：页面发出 intent，这里落到 ViewModel 与周期位移。
 * ViewModel 与平台能力因此只出现在组合根侧。
 */
private class StatisticsActionsAdapter(
    private val planningActions: StatisticsPlanningActions,
    private val selection: StatisticsSelection,
    private val currentDate: () -> LocalDate,
    private val onOpenSettings: () -> Unit,
) : StatisticsActions {
    override fun selectPreviousPeriod() = shift(-1)

    override fun selectNextPeriod() = shift(1)

    private fun shift(amount: Long) {
        selection.shiftPeriod(amount, currentDate())
    }

    override fun selectCadence(cadence: GoalCadence) {
        selection.selectCadence(cadence)
    }

    override fun settlePeriodSnapshots() {
        planningActions.settlePeriodSnapshots()
    }

    override fun cancelMigration(migrationId: String) {
        planningActions.cancelMigration(migrationId)
    }

    override fun awardMilestones() {
        planningActions.awardMilestones()
    }

    // 设置页从统计页进入：顶级页面仍然是统计，因此这里不改变 destination。
    override fun openSettings() = onOpenSettings()
}

/**
 * 目标树页的 actions adapter，在组合根一侧组装：页面发出 intent，这里落到 ViewModel，
 * 并在这里构造领域对象（`FutureGoalRule`、`Migration`）与生成 id。
 * ViewModel、Room 与 id 生成器因此只出现在组合根侧。
 */
private class TargetsActionsAdapter(
    private val planningActions: TargetPlanningActions,
    private val currentState: () -> AttentionState,
) : TargetsActions {
    override fun addTarget(title: String, parentId: String?) {
        planningActions.addTarget(title, parentId)
    }

    override fun renameTarget(targetId: String, title: String) {
        planningActions.renameTarget(targetId, title)
    }

    override fun addGoal(
        targetId: String,
        cadence: GoalCadence,
        targetMinutes: Int,
        startDate: String,
        dueDate: String?,
    ) {
        planningActions.addGoal(targetId, cadence, targetMinutes, startDate, dueDate)
    }

    override fun assignUnowned(timeEntryIds: Set<String>, targetId: String) {
        planningActions.assignUnowned(timeEntryIds, targetId)
    }

    override fun saveFutureGoal(
        targetId: String,
        ruleId: String?,
        cadence: GoalCadence,
        targetMinutes: Int,
        effectiveFrom: String,
        dueDate: String?,
        onDate: LocalDate,
    ) {
        val rule = FutureGoalRule(
            id = ruleId ?: newId(),
            targetId = targetId,
            cadence = cadence,
            targetMinutes = targetMinutes,
            effectiveFrom = effectiveFrom,
            dueDate = dueDate,
        )
        if (ruleId == null) planningActions.addFutureGoal(rule, onDate) else planningActions.updateFutureGoal(rule, onDate)
    }

    override fun cancelFutureGoal(ruleId: String, onDate: LocalDate) {
        planningActions.cancelFutureGoal(ruleId, onDate)
    }

    override fun appendOneTimeGoal(
        targetId: String,
        targetMinutes: Int,
        startDate: String,
        dueDate: String?,
        onSuccess: () -> Unit,
    ) {
        planningActions.appendOneTimeGoal(targetId, targetMinutes, startDate, dueDate, onSuccess)
    }

    override fun saveMigration(
        targetId: String,
        migrationId: String?,
        sourceStageId: String,
        minutes: Int,
        destinationStartDate: String,
        destinationEndDate: String?,
        onDate: LocalDate,
    ) {
        val migration = Migration(
            id = migrationId ?: newId(),
            targetId = targetId,
            sourceStageId = sourceStageId,
            minutes = minutes,
            destinationStartDate = destinationStartDate,
            destinationEndDate = destinationEndDate,
            cancelled = false,
        )
        if (migrationId == null) planningActions.addMigration(migration, onDate) else planningActions.updateMigration(migration, onDate)
    }

    override fun cancelMigration(migrationId: String) {
        planningActions.cancelMigration(migrationId)
    }

    override fun toggleTarget(targetId: String, expanded: Boolean) {
        planningActions.toggleTarget(targetId, expanded)
    }

    override fun reorderTarget(targetId: String, newIndex: Int) {
        planningActions.reorderTarget(targetId, newIndex)
    }

    override fun archiveTarget(targetId: String) {
        planningActions.archiveTarget(targetId)
    }

    override fun restoreTargets(targetIds: Set<String>) {
        planningActions.restoreTargets(targetIds)
    }

    override fun deleteTargetRelations(targetIds: Set<String>) {
        planningActions.deleteTargetRelations(targetIds)
    }

    override fun moveTargetFrom(targetId: String, parentId: String?, effectiveFrom: String) {
        planningActions.moveTargetFrom(targetId, parentId, effectiveFrom)
    }

    override fun updateFutureTargetMove(moveId: String, parentId: String?, effectiveFrom: String) {
        val existing = currentState().targetMoves.firstOrNull { it.id == moveId } ?: return
        planningActions.updateFutureTargetMove(existing.copy(parentId = parentId, effectiveFrom = effectiveFrom))
    }

    override fun cancelFutureTargetMove(moveId: String) {
        planningActions.cancelFutureTargetMove(moveId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceApp(openScheduleDate: String? = null, openScheduleId: String? = null) {
    val context = LocalContext.current
    val database = remember { AttentionDatabase.create(context) }
    val repository = remember {
        RoomAttentionStateRepository(
            business = RoomBusinessDataRepository(database),
            settings = context.createSettingsStore(),
            backupStore = FileImportBackupStore(context),
            reminderCoordinator = AlarmReminderCoordinator(ReminderScheduler(context)),
        )
    }
    val viewModel: AttentionViewModel = viewModel(factory = WorkspaceViewModelFactory(repository))
    // 助手运行时在组合根一处装配(ADR-0009 写入点):审计与快照只挂在包装通道上,
    // 手动派发路径零改动;本票不接任何 UI 触发,#230 注入 Reasoner、对话页接线后 run 才可达。
    rememberWorkspaceAgentRuntime(repository = repository, viewModel = viewModel)
    val investmentActions: InvestmentActions = viewModel
    val settingsPlanningActions: SettingsPlanningActions = viewModel
    val onboardingPlanningActions: OnboardingPlanningActions = viewModel
    val targetPlanningActions: TargetPlanningActions = viewModel
    val agendaPlanningActions: AgendaPlanningActions = viewModel
    val statisticsPlanningActions: StatisticsPlanningActions = viewModel
    val backupActions: BackupActions = viewModel
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val tokens = attentionTokens()
    // 短时提示的宿主状态只创建一次，挂在页面切换之上，因此五个页面共用同一个提示出口。
    val feedback = rememberAttentionFeedbackHostState()
    val feedbackScope = rememberCoroutineScope()
    val hasExternalScheduleRequest = openScheduleDate != null || openScheduleId != null
    // 启动优先级（外部日程请求 > 保存的启动入口 > 上次打开 > 当前规划日的日程）只在 :domain 解析，
    // 组合根不再自己实现一份（ADR-0005 第 1 节与「后续页面不得重复」）。
    var destination by remember {
        mutableStateOf(resolveInitialLaunchDestination(hasExternalScheduleRequest, state.launchDestination, state.lastOpenedDestination))
    }
    var destinationChosen by remember { mutableStateOf(hasExternalScheduleRequest) }
    // 启动落点是否已经落到一个「真实」的取值上，而不是仍停在暂定值上。
    //
    // [destination] 的初值是由**空的默认状态**解析出来的暂定值；状态真的读上来之后，
    // 下一个 LaunchedEffect 才把它纠正成用户保存的入口。这个纠正与「上次打开」的写回
    // 发生在同一次重组里：写回那条 effect 捕获到的是纠正之前的暂定值，于是会把暂定值
    // 当成用户真正停留的页面写回去，把「上次打开」永久改成日程（#179 设备实跑间歇失败）。
    // 因此写回额外要求落点已经确定：要么已被状态纠正，要么用户自己点过。
    // 外部请求**不算**落点已确定：它只是把用户送到日程，用户并没有因此选择过它（#185）。
    // 这一次外部送达由 [LastOpenedWriteBackFacts.fromExternalRequest] 单独挡住；
    // 用户在外部打开之后亲手点别的入口，onSelect 会把它置为已确定，那次切换照常记录。
    var destinationSettled by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // 引导是否被用户重新打开：设置页的「重新打开首次设置」切到独立设置页。
    // 自动出现的那一次由持久化的引导状态决定，不由这个变量决定。
    var onboardingRequested by remember { mutableStateOf(false) }
    // 引导状态直接读设置快照，并允许「还没读到」这个第三种状态：
    // 未读到时不显示引导，因此已完成引导的老用户不会先看到一帧首次设置页。
    val persistedSettings by remember { context.createSettingsStore().settings }
        .collectAsStateWithLifecycle(initialValue = null)
    var persistedSettingsLoaded by remember { mutableStateOf(false) }
    var jsonPreview by remember { mutableStateOf<String?>(null) }
    var pendingJsonBackup by remember { mutableStateOf<ExportDocument?>(null) }
    var jsonExportMessage by remember { mutableStateOf<String?>(null) }
    var pendingTimeEntriesCsv by remember { mutableStateOf<ExportDocument?>(null) }
    var pendingCsvBundle by remember { mutableStateOf<ExportBundle?>(null) }
    var csvBundleMessage by remember { mutableStateOf<String?>(null) }
    val timeEntriesCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        val document = pendingTimeEntriesCsv
        pendingTimeEntriesCsv = null
        if (uri == null) {
            feedbackScope.launch { feedback.showMessage("已取消保存时间记录 CSV") }
        } else if (document == null) {
            feedbackScope.launch { feedback.showMessage("时间记录 CSV 导出失败") }
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    writeUtf8Csv(document.content, output)
                } ?: error("无法打开保存位置")
            }.onSuccess {
                feedbackScope.launch { feedback.showMessage("时间记录 CSV 已保存") }
            }.onFailure {
                feedbackScope.launch { feedback.showMessage("时间记录 CSV 保存失败：${it.message ?: "未知错误"}") }
            }
        }
    }
    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val document = pendingJsonBackup
        pendingJsonBackup = null
        if (uri == null) {
            jsonExportMessage = "已取消保存 JSON 备份"
        } else if (document == null) {
            jsonExportMessage = "JSON 备份导出失败：没有可保存的数据"
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.let { output ->
                    writeUtf8Json(document.content, output)
                } ?: error("无法打开保存位置")
            }.onSuccess {
                jsonExportMessage = "JSON 备份已保存"
            }.onFailure {
                jsonExportMessage = "JSON 备份保存失败：${it.message ?: "无法写入文件"}"
            }
        }
    }
    var csvPreview by remember { mutableStateOf<String?>(null) }
    var pendingScheduleCsv by remember { mutableStateOf<ExportDocument?>(null) }
    var scheduleExportMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<ImportPlan?>(null) }
    var pendingClearImport by remember { mutableStateOf<ImportPlan?>(null) }
    var restoreImportPromptVisible by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取所选文件")
            }
            result.onSuccess { encoded ->
                if (encoded.isNotBlank()) {
                    backupActions.prepareImport(
                        encoded = encoded,
                        clearExisting = false,
                        onResult = { plan -> pendingImport = plan },
                        onFailure = { error -> jsonExportMessage = "导入失败：${error.message ?: "无法解析文件"}" },
                    )
                } else {
                    jsonExportMessage = "导入失败：文件为空"
                }
            }.onFailure {
                jsonExportMessage = "导入失败：${it.message ?: "无法读取文件"}"
            }
        }
    }
    val scheduleExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val document = pendingScheduleCsv
        pendingScheduleCsv = null
        if (uri == null) {
            scheduleExportMessage = "已取消日程 CSV 导出"
        } else if (document == null) {
            scheduleExportMessage = "日程 CSV 导出失败：没有可保存的数据"
        } else {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("无法打开保存位置")
                output.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer -> writer.write(document.content) }
                }
            }.onSuccess {
                scheduleExportMessage = "日程 CSV 已保存"
            }.onFailure {
                scheduleExportMessage = "日程 CSV 保存失败：${it.message ?: "无法写入文件"}"
            }
        }
    }
    val combinedScheduleExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val bundle = pendingCsvBundle
        pendingCsvBundle = null
        if (uri == null) {
            csvBundleMessage = "时间记录 CSV 已保存；已取消保存日程 CSV"
        } else if (bundle == null) {
            csvBundleMessage = "时间记录 CSV 已保存；日程 CSV 导出失败"
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> writeUtf8Csv(bundle.schedules.content, output) }
                    ?: error("无法打开保存位置")
            }.onSuccess {
                csvBundleMessage = "时间记录 CSV 和日程 CSV 已保存"
            }.onFailure {
                csvBundleMessage = "时间记录 CSV 已保存；日程 CSV 保存失败：${it.message ?: "未知错误"}"
            }
        }
    }
    val combinedTimeExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val bundle = pendingCsvBundle
        if (uri == null) {
            csvBundleMessage = "已取消保存时间记录 CSV；未开始保存日程 CSV"
            pendingCsvBundle = null
        } else if (bundle == null) {
            csvBundleMessage = "时间记录 CSV 导出失败；未开始保存日程 CSV"
            pendingCsvBundle = null
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> writeUtf8Csv(bundle.timeEntries.content, output) }
                    ?: error("无法打开保存位置")
            }.onSuccess {
                csvBundleMessage = "时间记录 CSV 已保存；请选择日程 CSV 的保存位置"
                combinedScheduleExportLauncher.launch(bundle.schedules.suggestedFileName)
            }.onFailure {
                csvBundleMessage = "时间记录 CSV 保存失败：${it.message ?: "未知错误"}；未开始保存日程 CSV"
                pendingCsvBundle = null
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(state.launchDestination, state.lastOpenedDestination) {
        if (!destinationChosen && !hasExternalScheduleRequest) {
            destination = resolveLaunchDestination(state.launchDestination, state.lastOpenedDestination)
            // 落点此刻才是用户保存的那个；在这之前写回去的都是暂定值。
            destinationSettled = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        context.createSettingsStore().settings.first()
        persistedSettingsLoaded = true
    }
    // 「上次打开」写回之前必须等 ViewModel 真的把持久化状态读上来（[state] 在那之前是一份空的
    // 默认值），并且必须等启动落点已经确定：落点的初值也是由那份空默认值解析出来的暂定值，
    // 与状态纠正发生在同一次重组里，写回那条 effect 捕获到的正是纠正之前的暂定值。
    // 把它抢先写回去会永久覆盖用户上次真正停留的页面（#174、#179 设备实跑间歇失败）。
    val stateLoaded by viewModel.stateLoaded.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(destination, settingsOpen, persistedSettingsLoaded, stateLoaded, destinationSettled) {
        // 底部导航只有三项，因此「上次打开」写入的永远是它自己：旧入口值先按领域兼容映射落成日程。
        if (shouldPersistLastOpened(
                LastOpenedWriteBackFacts(
                    settingsLoaded = persistedSettingsLoaded,
                    settingsOpen = settingsOpen,
                    stateLoaded = stateLoaded,
                    destinationSettled = destinationSettled,
                    fromExternalRequest = hasExternalScheduleRequest,
                ),
            )
        ) {
            viewModel.saveLastOpened(destination.asAgendaEntry())
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.restoreReminders() }

    // 返回键只处理当前可关闭的层（ADR-0005 第 4 节：平台行为归组合根）。
    // 落点由纯函数决定：主页面之间没有返回栈，因此这里不看标签历史——
    // 在日程、目标树、统计之间来回切换之后按返回键不会逐页回放标签。
    // 设置页是唯一挂在主内容里、而不是对话框窗口里的可关闭层，必须由返回键关掉；
    // 日程详情对话框与日期选择器在自己的窗口里先消费掉返回键，优先级天然更高。
    when (resolveBackTarget(settingsOpen)) {
        WorkspaceBackTarget.CLOSE_SETTINGS_PAGE -> BackHandler(enabled = true) { settingsOpen = false }
        // 没有可关闭的层就不注册处理器：返回键原样交给系统，退出行为不变（#175）。
        WorkspaceBackTarget.SYSTEM -> Unit
    }

    // 系统时钟只在组合根读一次，所有页面投影共用这一份 now 与 zone（ADR-0005 第 3 节）。
    val injectedNow = Instant.now()
    val injectedZone = ZoneId.systemDefault()

    // 所有计时入口共用同一个平台效果 seam。领域命令先提交，ViewModel 的成功回调
    // 再触发这里；因此平台失败不会撤销已保存的时间记录，页面也不需要知道 Android API。
    val timerEffects: TimerEffects = remember(context) {
        AndroidTimerEffects(context) { message ->
            feedbackScope.launch { feedback.showMessage(message) }
        }
    }
    androidx.compose.runtime.LaunchedEffect(stateLoaded, state.activeTimer) {
        if (stateLoaded) timerEffects.synchronize(state)
    }

    // 两个启动入口只传不同的领域来源参数，平台效果走同一条路径。
    fun startTimer(targetId: String?, scheduleEntryId: String?) {
        investmentActions.startTimer(
            targetId = targetId,
            onSuccess = { timerEffects.started() },
            scheduleEntryId = scheduleEntryId,
        )
    }

    // 从日程条目起计时保留来源关联；自由计时明确不带日程来源。
    fun startTimerFromSchedule(entry: ScheduleEntry) = startTimer(entry.targetId, entry.id)
    fun startFreeTimer(targetId: String?) = startTimer(targetId, null)

    val currentWeekStartDay by androidx.compose.runtime.rememberUpdatedState(state.settings.weekStartDay)
    // 日程页的选中范围由页面 module 提供类型与迁移语义，组合根只持有一个实例来喂投影。
    // 这一个实例挂在组合根上、活过页面切换，因此会话内的日期筛选被保留；
    // 它不写入任何持久化，重新启动时组合根新建实例，页面回到当前规划日。
    // 组合根把当前规划日喂给选择状态问「你选的是什么」，不解释 null 与规划日之间的语义。
    //
    // 打开定位的优先级：外部日程请求点名的规划日 > 当前规划日（深链优先级由 #174 决定）。
    // 外部请求点名了规划日就定位到那一天（并由 openScheduleId 定位到条目本身）；
    // 没有外部请求时选择状态没有显式选择，因此跟随当前规划日。
    val externalPlanningDate = externalSchedulePlanningDate(state, openScheduleDate, openScheduleId)
    val agendaSelection = rememberAgendaSelection(externalPlanningDate)
    val agendaPlanningDay = state.planningDate(injectedNow, injectedZone)
    val stoppedEntryIds by viewModel.stoppedEntryIds.collectAsStateWithLifecycle()
    // 「还在读」与「读失败」都是组合根掌握的事实：页面不调仓储，也不自己判断空白是不是加载中。
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val agendaLoad = agendaLoadFacts(stateLoaded, loadError)
    val agendaState = agendaScreenState(
        state = state,
        now = injectedNow,
        zone = injectedZone,
        selectedDate = agendaSelection.selectedDate(agendaPlanningDay),
        // 范围高亮来自选择状态自己记住的显式范围，跨规划日界线也不会丢。
        selectedScope = agendaSelection.selectedRangeScope(agendaPlanningDay),
        openScheduleId = openScheduleId,
        // 「刚刚停止的那一笔」由组合根交出：停止命令成功后 ViewModel 记下这次产出的记录 id，
        // 用户消费结果时清空。页面因此不缓存任何历史，也不会反复展示陈旧结果。
        stoppedEntryIds = stoppedEntryIds,
        loading = agendaLoad.loading,
        loadError = agendaLoad.loadError,
    )

    // 统计页的周期选择由统计页 module 提供类型与迁移语义，组合根只持有一个实例来喂投影。
    val statisticsSelection = rememberStatisticsSelection()
    val statisticsState = statisticsScreenState(
        state = state,
        now = injectedNow,
        zone = injectedZone,
        selectedDate = statisticsSelection.selectedDate,
        cadence = statisticsSelection.cadence,
    )
    val currentStatisticsDate by androidx.compose.runtime.rememberUpdatedState(statisticsState.selectedDate)
    val statisticsActions: StatisticsActions = remember(viewModel) {
        StatisticsActionsAdapter(
            planningActions = statisticsPlanningActions,
            selection = statisticsSelection,
            currentDate = { currentStatisticsDate },
            // 设置页只是统计页底部的一个去处：顶级页面不变，因此「上次打开」不受影响。
            onOpenSettings = { settingsOpen = true },
        )
    }

    // 设置页的 adapter 在组合根组装：备份与恢复原本在统计页，现在挂在设置页上，
    // 但落地的 SAF 文件选择器、导入预览与恢复确认一个都没变，因此 workflow 与迁移前一致。
    val settingsActions: SettingsActions = remember(viewModel) {
        SettingsActionsAdapter(
            planningActions = settingsPlanningActions,
            currentWeekStartDay = { currentWeekStartDay },
            onRequestReminderPermission = { requestReminderPermission(context) },
            onPreviewJsonBackup = {
                jsonExportMessage = null
                backupActions.exportJsonDocument { document ->
                    jsonPreview = document.content.take(800)
                    pendingJsonBackup = document
                    jsonExportLauncher.launch(document.suggestedFileName)
                }
            },
            onImportJson = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            onExportTimeEntriesCsv = {
                backupActions.exportTimeEntriesCsvDocument { document ->
                    pendingTimeEntriesCsv = document
                    timeEntriesCsvLauncher.launch(document.suggestedFileName)
                }
            },
            onPreviewCsv = {
                backupActions.exportCsvBundleDocument { bundle ->
                    csvPreview = "时间记录\n${bundle.timeEntries.content}\n日程\n${bundle.schedules.content}"
                }
            },
            onExportSchedulesCsv = {
                scheduleExportMessage = null
                backupActions.exportSchedulesCsvDocument { document ->
                    pendingScheduleCsv = document
                    scheduleExportLauncher.launch(document.suggestedFileName)
                }
            },
            onExportCsvBundle = {
                csvBundleMessage = null
                backupActions.exportCsvBundleDocument { bundle ->
                    pendingCsvBundle = bundle
                    combinedTimeExportLauncher.launch(bundle.timeEntries.suggestedFileName)
                }
            },
            onRequestRestoreImportBackup = { restoreImportPromptVisible = true },
            // 重新打开首次设置：先把设置页让出去，再显示独立首次设置页。
            onOpenOnboarding = {
                settingsOpen = false
                onboardingRequested = true
            },
            // 返回时回到统计页：设置页从统计进入，所以 destination 一直是统计。
            onClose = { settingsOpen = false },
        )
    }

    // 目标树页的选择与表单草稿由目标树页 module 提供类型与迁移语义，
    // 组合根只持有一个实例来喂投影，不改写它的字段。
    val targetsSelection = rememberTargetsSelection(state.planningDate(injectedNow, injectedZone))
    val targetsState = targetsScreenState(
        state = state,
        now = injectedNow,
        zone = injectedZone,
        selection = targetsSelection.snapshot(),
    )
    val currentAttentionState by androidx.compose.runtime.rememberUpdatedState(state)
    val targetsActions: TargetsActions = remember(viewModel) {
        TargetsActionsAdapter(
            planningActions = targetPlanningActions,
            currentState = { currentAttentionState },
        )
    }

    // 日程页的 adapter 在组合根组装：计时服务启动与提醒权限申请都属于这里，页面只发 intent。
    val currentAgendaDate by androidx.compose.runtime.rememberUpdatedState(
        agendaState.selectedDate ?: agendaState.planningDate,
    )
    val currentAgendaPlanningDay by androidx.compose.runtime.rememberUpdatedState(agendaState.planningDate)
    val agendaActions: AgendaActions = remember(viewModel) {
        AgendaActionsAdapter(
            investmentActions = investmentActions,
            planningActions = agendaPlanningActions,
            selection = agendaSelection,
            currentDate = { currentAgendaDate },
            currentPlanningDay = { currentAgendaPlanningDay },
            zone = injectedZone,
            onStartTimerFromEntry = ::startTimerFromSchedule,
            onStartFreeTimer = ::startFreeTimer,
            onRequestReminderPermission = { requestReminderPermission(context) },
            onStopTimer = {
                investmentActions.stopTimer(onSuccess = {
                    timerEffects.stopped()
                })
            },
            onPauseTimer = { investmentActions.pauseTimer(onSuccess = timerEffects::paused) },
            onResumeTimer = { investmentActions.resumeTimer(onSuccess = timerEffects::resumed) },
            onRecoverTimerFromNow = {
                investmentActions.recoverTimer(onSuccess = {
                    timerEffects.recovered()
                })
            },
            onSwitchTimer = { targetId ->
                investmentActions.switchTimer(targetId, onSuccess = timerEffects::resumed)
            },
            onTimeRecordDeleted = { removed ->
                feedbackScope.launch {
                    feedback.showUndoableMessage(
                        message = agendaFeedbackMessage(AgendaOperationFeedback.TIME_DELETED),
                        undoLabel = AGENDA_UNDO_LABEL,
                        onUndo = { investmentActions.restoreTimeEntry(removed) },
                    )
                }
            },
            // 容量摘要的完整设置入口：设置仍然住在统计页底部的「设置与数据」，
            // 因此这里先把顶级页面让给统计，再把设置页举起来，返回时自然回到统计页。
            onOpenSettings = {
                destinationChosen = true
                destinationSettled = true
                destination = LaunchDestination.STATISTICS
                settingsOpen = true
            },
            // 成功确认走同一条反馈宿主通路：与删除撤销共用一个 Snackbar 出口，
            // 因此整个工作区只有一套短时提示方式。
            onFeedback = { operation ->
                feedbackScope.launch { feedback.showMessage(agendaFeedbackMessage(operation)) }
            },
        )
    }

    // 首次设置是独立页面：首次启动可跳过，之后从设置页再次打开。
    val onboardingVisible = onboardingRequested || persistedSettings?.onboardingCompleted == false
    val onboardingState = onboardingScreenState(
        planningDate = agendaPlanningDay.toString(),
        settings = state.settings,
    )
    val onboardingActions: OnboardingActions = remember(viewModel) {
        OnboardingActionsAdapter(
            planningActions = onboardingPlanningActions,
            onCompleted = {
                // 完成或跳过都落到当前规划日的日程：离开引导、离开设置页，并把日程页的
                // 日期范围交回给当前规划日（页面侧的语义由页面 module 提供）。
                onboardingRequested = false
                settingsOpen = false
                destinationChosen = true
                destination = LaunchDestination.AGENDA
                agendaSelection.followPlanningDay()
            },
        )
    }

    Scaffold(
        // Prototype A uses the compact workbench itself as the page header. The generic
        // Material top bar added a large empty brand band above the date controls.
        bottomBar = {
            if (!onboardingVisible) {
                // 主工作区收敛为日程、目标树、统计三个入口：今天/明天/所有日期是日程页里的范围，
                // 设置不再是顶级入口——它从统计页底部的「设置与数据」进入，返回时回到统计页。
                // 每一项同时有 Material 图标与中文标签，选中态带可访问语义。
                // 活动是 edge-to-edge 的，而这里是自定义底栏（不是自带 insets 的 NavigationBar）：
                // 不自己垫出系统导航栏高度，三项就会压在系统手势条下面。
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(
                        Triple(LaunchDestination.AGENDA, "日程", Icons.Filled.Event),
                        Triple(LaunchDestination.TARGETS, "目标树", Icons.Filled.AccountTree),
                        Triple(LaunchDestination.STATISTICS, "统计", Icons.Filled.Insights),
                    ).forEach { (item, label, icon) ->
                        WorkspaceNavigationItem(
                            label = label,
                            icon = icon,
                            // 设置页挂在统计页底下，因此停留在设置页时统计仍然处于选中态。
                            selected = destination.asAgendaEntry() == item,
                            onSelect = {
                                // 从设置页切回主工作区：先离开设置，再换顶级页面。
                                settingsOpen = false
                                destinationChosen = true
                                // 用户亲手点的落点一定是真实的：这一次的写回不必再等状态纠正。
                                destinationSettled = true
                                destination = item
                            },
                            modifier = Modifier.testTag("nav-${item.name.lowercase()}"),
                        )
                    }
                }
            }
        },
    // 短时提示宿主挂在 Scaffold 上，因此五个页面共用同一个出口，流程不被打断。
        snackbarHost = { AttentionSnackbarHost(state = feedback) },
    ) { padding ->
        if (onboardingVisible) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                OnboardingScreen(state = onboardingState, actions = onboardingActions)
            }
        } else Column(Modifier.padding(padding).fillMaxSize()) {
            error?.let {
                AttentionInlineError(
                    message = it,
                    retryLabel = "关闭提示",
                    onRetry = viewModel::clearError,
                    modifier = Modifier.padding(horizontal = tokens.spacing.large),
                )
            }
            if (settingsOpen) {
                SettingsScreen(
                    state = settingsScreenState(
                        state = state,
                        now = injectedNow,
                        zone = injectedZone,
                        // 通知是否可用是平台事实，由组合根查好后注入，页面自己不判断。
                        notificationsEnabled = notificationsEnabled(context),
                    ),
                    actions = settingsActions,
                )
            } else when (destination.toTopLevelWorkspace()) {
                // 「日程」是合并后的页面：它承载当前规划日、今天、明天、所有日期与任意规划日。
                // 旧入口值 TODAY / ALL_DATES 通过领域兼容映射落到同一页（枚举值本身保留）。
                LaunchDestination.TARGETS -> TargetsScreen(
                    state = targetsState,
                    actions = targetsActions,
                    selection = targetsSelection,
                )
                LaunchDestination.STATISTICS -> StatisticsScreen(
                    state = statisticsState,
                    actions = statisticsActions,
                )
                // 「日程」与兜底落在同一页：「日程」是合并后的页面，它承载当前规划日、今天、
                // 明天、所有日期与任意规划日；旧入口值 TODAY / ALL_DATES 由领域的兼容映射折进来
                // （枚举值本身保留），LAST_OPENED 这类不是顶级工作区的值由 [toTopLevelWorkspace]
                // 回退到日程。空白工作区对用户没有任何解释，是比落错页更差的失败方式。
                else -> AgendaScreen(
                    state = agendaState,
                    actions = agendaActions,
                    openScheduleId = openScheduleId,
                )
            }
            jsonPreview?.let { Text("JSON 预览：\n$it", modifier = Modifier.padding(16.dp)) }
            jsonExportMessage?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            csvPreview?.let { Text("CSV 预览：\n${it.take(800)}", modifier = Modifier.padding(16.dp)) }
            scheduleExportMessage?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            csvBundleMessage?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
    }
    pendingImport?.let { plan ->
        AlertDialog(
            onDismissRequest = {
                pendingImport = null
            },
            title = { Text("导入预览") },
            text = {
                Text(
                    plan.preview.render(plan.mode),
                )
            },
            confirmButton = {
                AttentionTextButton(onClick = {
                    backupActions.commitImport(
                        plan,
                        onResult = { result ->
                            if (result is BackupCommitResult.Committed || result is BackupCommitResult.StalePlan) {
                                pendingImport = null
                            }
                        },
                    )
                }) { Text("合并导入") }
            },
            dismissButton = {
                Row {
                    AttentionTextButton(onClick = {
                        pendingClearImport = plan.forReplacement()
                        pendingImport = null
                    }) { Text("清空后恢复") }
                    AttentionTextButton(onClick = {
                        pendingImport = null
                    }) { Text("取消") }
                }
            },
        )
    }
    pendingClearImport?.let { plan ->
        AlertDialog(
            onDismissRequest = {
                pendingClearImport = null
            },
            title = { Text("确认清空后恢复") },
            text = {
                Text(
                    plan.preview.render(plan.mode) +
                        "\n这会清空当前全部数据，并在导入前保存保护性备份。确定继续吗？",
                )
            },
            confirmButton = {
                AttentionTextButton(onClick = {
                    backupActions.commitImport(
                        plan,
                        onResult = { result ->
                            if (result is BackupCommitResult.Committed || result is BackupCommitResult.StalePlan) {
                                pendingClearImport = null
                            }
                        },
                    )
                }) { Text("确认清空并恢复") }
            },
            dismissButton = {
                AttentionTextButton(onClick = {
                    pendingClearImport = null
                }) { Text("取消") }
            },
        )
    }
    if (restoreImportPromptVisible) {
        AlertDialog(
            onDismissRequest = { restoreImportPromptVisible = false },
            title = { Text("确认恢复导入前备份", modifier = Modifier.testTag("restore-import-dialog")) },
            text = { Text("将当前状态替换为最近一次导入前保存的备份，是否继续？") },
            confirmButton = {
                AttentionTextButton(
                    modifier = Modifier.testTag("restore-import-confirm"),
                    onClick = {
                        restoreImportPromptVisible = false
                        backupActions.restoreLastImportBackupResult(onResult = { result ->
                            if (result is BackupCommitResult.NoImportBackup) {
                                jsonExportMessage = "没有可恢复的导入前备份"
                            }
                        })
                    },
                ) { Text("确认恢复") }
            },
            dismissButton = {
                AttentionTextButton(
                    modifier = Modifier.testTag("restore-import-cancel"),
                    onClick = { restoreImportPromptVisible = false },
                ) { Text("取消") }
            },
        )
    }
}

/**
 * 一个底部导航入口：Material 图标与中文标签同时可见，选中态用填充色之外的
 * 「已选中：…」文案、勾选图标与可访问的 selected 语义表达，因此不依赖颜色单独传达。
 */
@Composable
private fun WorkspaceNavigationItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(attentionTokens().radius.small),
        color = Color.Transparent,
        modifier = modifier.selectable(
            selected = selected,
            onClick = onSelect,
            role = Role.Tab,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = "已选中", modifier = Modifier.size(16.dp))
            }
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Text(label)
        }
    }
}

private fun requestReminderPermission(context: Context) {
    if (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) return
    context.findActivity()?.let {
        ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REMINDER_PERMISSION_REQUEST_CODE)
    }
}

private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val REMINDER_PERMISSION_REQUEST_CODE = 4101

/**
 * 「上次打开」写回的事实。五个条件都成立才允许写回。
 *
 * 它们挡的是**同一个**后果：把用户上次真正停留的页面永久改成别的页面。因此用命名事实承载，
 * 而不是五个相邻的布尔——调用点必须逐个说清楚，否则「哪个 true 挡住了写回」无从读起。
 */
internal data class LastOpenedWriteBackFacts(
    /** 持久化设置确实读到了（不是首次组合时那份还没落地的快照）。 */
    val settingsLoaded: Boolean,
    /** 当前是否在设置页；设置页不是顶级工作区，不该被记成上次打开。 */
    val settingsOpen: Boolean,
    /** 领域状态是否真的加载完成；之前 [state] 只是一份空默认值。 */
    val stateLoaded: Boolean,
    /** 启动落点是否已经确定（已被状态纠正，或用户亲手点过）。 */
    val destinationSettled: Boolean,
    /** 这次打开是不是由外部日程请求送达的。 */
    val fromExternalRequest: Boolean,
)

/**
 * 「上次打开」能不能写回去。
 *
 * 前两个条件是边界：[settingsLoaded] 没成立说明设置还没读上来，[settingsOpen] 时用户人在设置页。
 *
 * 后三个条件挡的是同一种写回：[state] 在加载完成之前是一份空的默认值，启动落点 [WorkspaceApp] 里的
 * [destination] 初值又是由那份空默认值解析出来的暂定值，据此写回去会永久覆盖用户上次真正停留的页面。
 * 状态纠正与写回发生在同一次重组里，所以「状态已加载」并不蕴含「落点已纠正」——两者必须分别判断。
 *
 * [LastOpenedWriteBackFacts.fromExternalRequest] 挡的是另一件事：外部日程请求只负责把用户送到指定规划日
 * 与指定条目，用户因此被动看到日程页。若把这次送达记成上次打开，启动入口为「上次打开」的用户会在一次提醒
 * 之后被永久钉在日程页，「上次打开」也就不是上次打开了。外部请求打开之后用户亲手切换工作区不算外部送达，
 * 那一次切换照常记录。
 */
internal fun shouldPersistLastOpened(facts: LastOpenedWriteBackFacts): Boolean =
    facts.settingsLoaded && !facts.settingsOpen && facts.stateLoaded &&
        facts.destinationSettled && !facts.fromExternalRequest

/**
 * 一次返回键按下时它该落在哪里。返回键处理是平台行为，因此决策点留在组合根侧，
 * 并且写成纯函数：每种情形都能在 JVM 测试里钉住，不必依赖设备与窗口顺序。
 */
internal enum class WorkspaceBackTarget {
    /** 关掉当前打开的设置页。 */
    CLOSE_SETTINGS_PAGE,

    /** 没有可关闭的层：把返回键交回系统，应用退出行为与本票之前一致。 */
    SYSTEM,
}

/**
 * 只看「有没有可关闭的层」，不看标签历史——主页面之间的切换不形成逐页返回栈，
 * 因此标签切换多少次都不会让返回键去回放上一个标签页。
 */
internal fun resolveBackTarget(settingsOpen: Boolean): WorkspaceBackTarget =
    if (settingsOpen) WorkspaceBackTarget.CLOSE_SETTINGS_PAGE else WorkspaceBackTarget.SYSTEM
