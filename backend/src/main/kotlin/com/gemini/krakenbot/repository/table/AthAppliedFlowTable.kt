package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/**
 * Identities of owner-capital ledger events whose ATH scaling has been
 * durably applied. The ATH value, this journal, and the flow watermark are
 * written in a single SQLite transaction, so a crash can neither lose an
 * event nor apply one twice: unrecorded events are retried, recorded events
 * are skipped. Ledger identity (not just a timestamp watermark) keeps
 * same-second events exact. Rows at or below the watermark are redundant and
 * pruned in the same transaction.
 */
object AthAppliedFlowTable : Table("ath_applied_flows") {
    val ledgerId = varchar("ledger_id", 128)
    val eventTimeSec = long("event_time_sec")

    override val primaryKey = PrimaryKey(ledgerId)
}
