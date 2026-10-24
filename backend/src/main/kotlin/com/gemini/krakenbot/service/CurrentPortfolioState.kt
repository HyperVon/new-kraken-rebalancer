package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

/** Process-local portfolio snapshots and cycle activities for the Dashboard. */
class CurrentPortfolioState {
    private val mutableSnapshot = MutableStateFlow<PortfolioSnapshot?>(null)
    private val mutableActivityUpdate = MutableStateFlow<CurrentCycleActivity?>(null)
    private val mutableDashboardRevision = MutableStateFlow(0L)
    private val lock = Any()
    private val recent = ArrayDeque<PortfolioSnapshot>()
    private val recentActivities = ArrayDeque<CurrentCycleActivity>()
    private var sourceScope: String? = null
    private var activitySourceId = UUID.randomUUID().toString()

    val snapshot: StateFlow<PortfolioSnapshot?> = mutableSnapshot.asStateFlow()
    val activityUpdates: StateFlow<CurrentCycleActivity?> = mutableActivityUpdate.asStateFlow()
    val dashboardRevision: StateFlow<Long> = mutableDashboardRevision.asStateFlow()

    fun publish(snapshot: PortfolioSnapshot, sourceScope: String, activity: CurrentCycleActivity? = null) {
        synchronized(lock) {
            useSource(sourceScope)
            recent.addLast(snapshot)
            while (recent.size > MAX_RECENT_SNAPSHOTS) recent.removeFirst()
            activity?.let(::appendActivity)
            mutableSnapshot.value = snapshot
            advanceDashboardRevision()
        }
    }

    fun publishActivity(activity: CurrentCycleActivity, sourceScope: String) {
        synchronized(lock) {
            useSource(sourceScope)
            appendActivity(activity)
            advanceDashboardRevision()
        }
    }

    private fun advanceDashboardRevision() {
        mutableDashboardRevision.value += 1
    }

    /** Returns one consistent latest-and-trend view for the dashboard, scoped to this process. */
    fun readDashboardView(): CurrentPortfolioDashboardView = synchronized(lock) {
        CurrentPortfolioDashboardView(
            latest = mutableSnapshot.value,
            recentSnapshots = recent.toList(),
            recentActivities = recentActivities.sortedActivities(),
            activitySourceId = activitySourceId,
        )
    }

    private fun useSource(sourceScope: String) {
        if (this.sourceScope == sourceScope) return
        recent.clear()
        recentActivities.clear()
        this.sourceScope = sourceScope
        activitySourceId = UUID.randomUUID().toString()
        mutableSnapshot.value = null
        mutableActivityUpdate.value = null
    }

    private fun appendActivity(activity: CurrentCycleActivity) {
        recentActivities.removeAll { it.cycleId == activity.cycleId }
        recentActivities.addLast(activity)
        while (recentActivities.size > MAX_RECENT_ACTIVITIES) recentActivities.removeFirst()
        mutableActivityUpdate.value = activity
    }

    private fun ArrayDeque<CurrentCycleActivity>.sortedActivities(): List<CurrentCycleActivity> =
        sortedWith(compareByDescending<CurrentCycleActivity> { it.occurredAt }.thenByDescending { it.cycleId })

    companion object {
        const val MAX_RECENT_SNAPSHOTS = 2_880
        const val MAX_RECENT_ACTIVITIES = 120
    }
}

enum class CurrentCycleActivityMode { SIMULATION, DRY_RUN, LIVE }

enum class CurrentCycleOutcome { NO_TRADE, DRY_RUN_PLAN, ORDERS_ATTEMPTED, PROBLEM }

data class CurrentCycleActivity(
    val cycleId: String,
    val occurredAt: Instant,
    val mode: CurrentCycleActivityMode,
    val outcome: CurrentCycleOutcome?,
    val actions: List<String>,
)

data class CurrentPortfolioDashboardView(
    val latest: PortfolioSnapshot?,
    val recentSnapshots: List<PortfolioSnapshot>,
    val recentActivities: List<CurrentCycleActivity> = emptyList(),
    val activitySourceId: String = "",
)
