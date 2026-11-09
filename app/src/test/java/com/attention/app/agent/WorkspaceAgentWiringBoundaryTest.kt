package com.attention.app.agent

import com.attention.app.data.AttentionStateRepository
import com.attention.app.data.audit.AgentTraceEntry
import com.attention.app.data.audit.AiChangeAudit
import com.attention.domain.AttentionCommand
import com.attention.domain.AttentionCommandResult
import com.attention.domain.AttentionState
import com.attention.domain.LaunchDestination
import com.attention.domain.StoredSettings
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接线边界护栏:
 * - 手动路径一致性——直接经仓库派发(ViewModel 与计时服务走的通道)不触审计与快照,
 *   派发面与接线前一致;审计只挂在运行时包装的执行通道上。
 * - 依赖纯度——运行时组件的字段、方法与构造参数不出现任何 Android 平台类型,
 *   与 P2 工具面的反射断言同一口径。
 */
class WorkspaceAgentWiringBoundaryTest {

    /** 仓库替身:只覆盖被测面(状态与 command 派发),其余成员显式不支持。 */
    private class FakeAttentionStateRepository : AttentionStateRepository {
        val dispatched = mutableListOf<AttentionCommand>()
        private val current = MutableStateFlow(AttentionState())

        override val state: Flow<AttentionState> = current

        override suspend fun updateBusiness(transform: (AttentionState) -> AttentionState) {
            current.value = transform(current.value)
        }

        override suspend fun executeCommand(
            command: AttentionCommand,
            now: Instant,
            zone: ZoneId,
        ): AttentionCommandResult {
            dispatched += command
            return AttentionCommandResult(current.value)
        }

        override suspend fun toggleScheduleCompleted(entryId: String) = Unit
        override suspend fun setPlannerSettings(settings: StoredSettings) = Unit
        override suspend fun setPlanningDayBoundaryMinutes(minutes: Int) = Unit
        override suspend fun setWeekStartDay(day: Int) = Unit
        override suspend fun setDailyCapacityMinutes(minutes: Int?) = Unit
        override suspend fun setLaunchDestination(destination: LaunchDestination) = Unit
        override suspend fun setLastOpenedDestination(destination: LaunchDestination) = Unit
        override suspend fun setOnboardingCompleted(completed: Boolean) = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
        override suspend fun replace(state: AttentionState) = Unit
        override suspend fun restoreReminders() = Unit
        override suspend fun exportJson(): String = "{}"
        override suspend fun importJson(encoded: String, clearExisting: Boolean): AttentionState = current.value
        override suspend fun lastImportBackup(): String? = null
    }

    @Test
    fun manual_dispatch_through_the_repository_never_touches_the_audit() = runBlocking {
        val snapshots = RecordingSnapshotStore()
        val traces = RecordingTraceStore()
        val repository = FakeAttentionStateRepository()

        // 手动路径:与 ViewModel/计时服务同一通道,直接经仓库派发,不经任何包装
        repository.executeCommand(AttentionCommand.StopTimer, DISPATCH_NOW, DISPATCH_ZONE)

        assertEquals(1, repository.dispatched.size)
        assertEquals("手动派发不得触发快照", emptyList<AttentionState>(), snapshots.captured)
        assertEquals("手动派发不得写运行摘要", emptyList<AgentTraceEntry>(), traces.recorded)
    }

    @Test
    fun the_runtime_surface_has_no_platform_types() {
        val violations = mutableListOf<String>()
        listOf(WorkspaceAgentRuntime::class.java, AuditedCommandExecutor::class.java).forEach { clazz ->
            clazz.declaredFields.forEach { field ->
                if (field.type.name.startsWith("android")) {
                    violations += "${clazz.simpleName} 字段 ${field.name}:${field.type.name}"
                }
            }
            clazz.declaredMethods.forEach { method ->
                if (method.returnType.name.startsWith("android")) {
                    violations += "${clazz.simpleName} 方法 ${method.name} 返回 ${method.returnType.name}"
                }
                method.parameterTypes.filter { it.name.startsWith("android") }.forEach {
                    violations += "${clazz.simpleName} 方法 ${method.name} 参数 ${it.name}"
                }
            }
            clazz.constructors.forEach { constructor ->
                constructor.parameterTypes.filter { it.name.startsWith("android") }.forEach {
                    violations += "${clazz.simpleName} 构造参数 ${it.name}"
                }
            }
        }
        assertTrue("运行时组件面出现平台类型:$violations", violations.isEmpty())
    }
}
