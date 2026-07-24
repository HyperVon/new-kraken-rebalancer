package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Exposed table definition for action logs — normalizes the List<String> actions from snapshots. */
object ActionLogTable : Table("action_logs") {
    val id = integer("id").autoIncrement()
    val snapshotId =
        integer("snapshot_id")
            .references(PortfolioSnapshotTable.id, onDelete = ReferenceOption.CASCADE)
    val message = text("message")

    init {
        index("idx_actionlogs_snapshot_id", false, snapshotId)
    }

    override val primaryKey = PrimaryKey(id)
}
