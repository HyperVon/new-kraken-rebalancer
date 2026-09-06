package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/**
 * Lifetime decision log of owner-capital ledger events that have passed
 * through ATH reconciliation — scaled, netted to zero, or consciously
 * skipped. The ATH value, this journal, and the flow watermark are written
 * in a single SQLite transaction, so a crash can neither lose an event nor
 * apply one twice: unrecorded events are retried, recorded events are
 * skipped. Ledger identity (not just a timestamp watermark) keeps
 * same-second events exact and lets late-arriving backfill below an old
 * watermark still be decided exactly once. The identity rescan trusts this
 * journal as the complete record of decisions, so it is never pruned.
 */
object AthAppliedFlowTable : Table("ath_applied_flows") {
    val ledgerId = varchar("ledger_id", 128)
    val eventTimeSec = long("event_time_sec")
    val decisionCategory = varchar("decision_category", 32).nullable()
    val asset = varchar("asset", 16).nullable()
    val actualBalanceDelta = decimal("actual_balance_delta", 24, 8).nullable()
    val normalizedGroupId = varchar("normalized_group_id", 128).nullable()
    val decisionVersion = integer("decision_version").nullable()

    override val primaryKey = PrimaryKey(ledgerId)
}
