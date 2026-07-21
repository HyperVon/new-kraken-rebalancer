---
name: exposed-repository
description: Create or modify JetBrains Exposed ORM repository implementations following the project's established patterns — safeTransaction, cascade deletes, primary key targeting, and upsert operations.
---

# Exposed Repository Patterns

When creating or modifying Exposed ORM repository implementations, follow these
patterns exactly.

## Repository Class Structure

All repositories take a `Database` instance via constructor injection and use
a logger:

```kotlin
class SqliteMyRepositoryImpl(
    private val database: Database
) : MyRepository {

    private val log = LoggerFactory.getLogger(SqliteMyRepositoryImpl::class.java)
}
```

## Safe Transactions

Use the `Database.safeTransaction` extension from `RepositoryUtils.kt` for all
write operations. It wraps the transaction in error handling and re-throws as
`IOException`:

```kotlin
override fun save(item: MyItem) {
    database.safeTransaction(log, "Failed to save item to database") {
        MyTable.insert {
            it[column1] = item.field1
            it[column2] = item.field2
        }
    }
}
```

For read-only operations, use `transaction(database)` directly:

```kotlin
override fun load(): List<MyItem> {
    return transaction(database) {
        MyTable.selectAll()
            .orderBy(MyTable.timestamp, SortOrder.DESC)
            .map { row -> buildItemFromRow(row) }
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

Only fall back to multi-column matching when the primary key ID is unavailable
(e.g. records loaded from external APIs without IDs).

## Cascade Deletes

When deleting parent records that have child rows, **always** delete children
first to maintain referential integrity:

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

When inserting parent records with children, capture the generated parent ID
and use it for child inserts:

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

When loading snapshots for charts, downsample IDs in-memory to prevent browser
crashes:

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

- [ ] Write operations use `database.safeTransaction`
- [ ] Deletes cascade children before parents
- [ ] Updates target by primary key ID
- [ ] `BigDecimal` columns use proper scale (8 for crypto, 2 for USD)
- [ ] Null-safe defaults (`BigDecimal.ZERO`, `0L`) for aggregate results
- [ ] No FQNs — all Exposed types imported at the top
