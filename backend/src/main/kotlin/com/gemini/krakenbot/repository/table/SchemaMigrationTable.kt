package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/** Applied database schema versions; migrations are monotonic and recorded transactionally. */
object SchemaMigrationTable : Table("schema_migrations") {
    val version = integer("version")
    val name = varchar("name", 96)
    val appliedAt = long("applied_at")

    override val primaryKey = PrimaryKey(version)
}
