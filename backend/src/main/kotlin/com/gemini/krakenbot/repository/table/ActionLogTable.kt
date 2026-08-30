package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

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

    fun applyTo(builder: UpdateBuilder<*>, snapshotId: Int, messageText: String) {
        builder[ActionLogTable.snapshotId] = snapshotId
        builder[message] = messageText
    }
}
