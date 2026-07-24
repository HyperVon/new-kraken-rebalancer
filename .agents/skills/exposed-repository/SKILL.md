---
name: exposed-repository
description: Create or modify JetBrains Exposed ORM repository implementations following the project's established patterns — safeTransaction, cascade deletes, primary key targeting, and upsert operations.
---

# Exposed Repository Patterns

When creating or modifying Exposed ORM repository implementations and table schemas, follow these patterns exactly.

## Exposed 1.x Packages

This project uses Exposed **1.3.x**. Prefer these import roots:

- Table / column / operator APIs: `org.jetbrains.exposed.v1.core.*` (e.g. `Table`, `eq`, `and`, `inList`)
- JDBC query / DML APIs: `org.jetbrains.exposed.v1.jdbc.*` (e.g. `Database`, `selectAll`, `insert`, `update`, `deleteWhere`)
- Transactions: `org.jetbrains.exposed.v1.jdbc.transactions.transaction` and `JdbcTransaction` receivers
- Schema metadata caches: `org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata`

Do **not** use the legacy `org.jetbrains.exposed.sql.*` packages.

## Table & Schema Definitions

Define database tables extending `LongIdTable` or `Table` with explicit precision and foreign key cascading rules:

```kotlin
object TradeTable : LongIdTable("trades") {
    val timestamp = long("timestamp").index()
    val pair = varchar("pair", 32)
    val side = varchar("side", 8)
    val volume = decimal("volume", 18, 8)
    val usdAmount = decimal("usd_amount", 12, 2)
    val success = boolean("success").default(true)
}

object AssetSnapshotTable : LongIdTable("asset_snapshots") {
    val snapshotId = reference("snapshot_id", PortfolioSnapshotTable, onDelete = ReferenceOption.CASCADE)
    val symbol = varchar("symbol", 16)
    val balance = decimal("balance", 18, 8)
}
```

- **Cryptocurrency Amounts**: Use `decimal("column_name", 18, 8)` for crypto balances and trade volumes.
- **USD Valuations**: Use `decimal("column_name", 12, 2)` for fiat totals and valuations.
- **Foreign Keys**: Always specify `onDelete = ReferenceOption.CASCADE` on foreign key references.

## Repository Class Structure

All repositories take a `Database` instance via constructor injection and use a logger:

```kotlin
class SqliteTradeRepositoryImpl(
    private val database: Database
) : TradeRepository {

    private val log = LoggerFactory.getLogger(SqliteTradeRepositoryImpl::class.java)
}
```

## Safe Transactions & Coroutine Concurrency

Use `Database.safeTransaction` for all write operations, wrapped in `withContext(Dispatchers.IO)` when executing inside coroutines:

```kotlin
override suspend fun save(trade: TradeRecord) = withContext(Dispatchers.IO) {
    database.safeTransaction(log, "Failed to save trade to database") {
        TradeTable.insert {
            it[timestamp] = trade.timestamp.toEpochMilli()
            it[pair] = trade.pair
            it[side] = trade.side.name
            it[volume] = trade.volume
            it[usdAmount] = trade.usdAmount
        }
    }
}
```

For read-only operations, use `transaction(database)` directly:

```kotlin
override suspend fun loadAll(): List<TradeRecord> = withContext(Dispatchers.IO) {
    transaction(database) {
        TradeTable.selectAll()
            .orderBy(TradeTable.timestamp, SortOrder.DESC)
            .map { row -> buildTradeFromRow(row) }
    }
}
```

## Primary Key Targeting

**Always** target records by primary key ID for updates and deletions:

```kotlin
// CORRECT — target by primary key:
TradeTable.update({ TradeTable.id eq oldTrade.id }) {
    it.applyTradeFields(newTrade)
}

// WRONG — fragile multi-column matching:
TradeTable.update({ (TradeTable.timestamp eq ts) and (TradeTable.pair eq pair) }) { ... }
```

Only fall back to multi-column matching when the primary key ID is unavailable (e.g. records loaded from external APIs without IDs).

## Cascade Deletes

When deleting parent records that have child rows, **always** delete children first to maintain referential integrity:

```kotlin
override fun pruneSnapshotsOlderThan(cutoff: Instant): Int {
    return transaction(database) {
        val idsToDelete = PortfolioSnapshotTable
            .select(PortfolioSnapshotTable.id)
            .where { PortfolioSnapshotTable.timestamp less cutoff.toEpochMilli() }
            .map { it[PortfolioSnapshotTable.id] }

        if (idsToDelete.isNotEmpty()) {
            // Delete children FIRST
            AssetSnapshotTable.deleteWhere { snapshotId inList idsToDelete }
            ActionLogTable.deleteWhere { snapshotId inList idsToDelete }
            // Then delete parent
            PortfolioSnapshotTable.deleteWhere { id inList idsToDelete }
        }
        idsToDelete.size
    }
}
```

## Parent–Child Inserts

When inserting parent records with children, capture the generated parent ID and use it for child inserts:

```kotlin
private fun insertSnapshotWithChildren(snapshot: PortfolioSnapshot) {
    val snapshotId = PortfolioSnapshotTable.insert {
        it[timestamp] = snapshot.timestamp.toEpochMilli()
        it[totalValueUSD] = snapshot.totalValueUSD
    }[PortfolioSnapshotTable.id]

    for ((_, asset) in snapshot.assets) {
        AssetSnapshotTable.insert {
            it[AssetSnapshotTable.snapshotId] = snapshotId
            it[symbol] = asset.symbol.value
            it[balance] = asset.balance
        }
    }
}
```

## Upsert Operations

Prefer Exposed's `upsert` for idempotent write operations:

```kotlin
override fun setSyncMetadata(key: String, value: String) {
    database.safeTransaction(log, "Failed to upsert sync metadata") {
        HistorySyncMetadataTable.upsert(HistorySyncMetadataTable.key) {
            it[HistorySyncMetadataTable.key] = key
            it[HistorySyncMetadataTable.value] = value
        }
    }
}
```

## UpdateBuilder Helpers

Extract repeated column assignments into `UpdateBuilder` extension methods:

```kotlin
private fun UpdateBuilder<*>.applyTradeFields(trade: TradeRecord) {
    this[TradeTable.timestamp] = trade.timestamp.toEpochMilli()
    this[TradeTable.pair] = trade.pair
    this[TradeTable.side] = trade.side
    this[TradeTable.volume] = trade.volume
}
```

## Aggregate Queries

Use Exposed's aggregate functions with null-safe defaults:

```kotlin
val countCol = TradeTable.id.count()
val volumeCol = TradeTable.usdAmount.sum()

val row = TradeTable
    .select(countCol, volumeCol)
    .where { TradeTable.success eq true }
    .firstOrNull()

val totalTrades = row?.get(countCol) ?: 0L
val totalVolume = row?.get(volumeCol) ?: BigDecimal.ZERO
```

## Downsampling for Large Result Sets

When loading snapshots for charts, downsample IDs in-memory to prevent browser crashes:

```kotlin
val allIds = PortfolioSnapshotTable
    .select(PortfolioSnapshotTable.id)
    .where { ... }
    .map { it[PortfolioSnapshotTable.id] }

val downsampledIds = if (allIds.size <= 300) allIds
    else allIds.filterIndexed { index, _ -> index % (allIds.size / 300) == 0 }
```

## Checklist

Before submitting repository code, verify:

- [ ] Write operations use `database.safeTransaction` and `withContext(Dispatchers.IO)`
- [ ] Deletes cascade children before parents
- [ ] Updates target by primary key ID
- [ ] `BigDecimal` columns use proper scale (18, 8 for crypto, 12, 2 for USD)
- [ ] Null-safe defaults (`BigDecimal.ZERO`, `0L`) for aggregate results
- [ ] No FQNs — all Exposed types imported at the top
