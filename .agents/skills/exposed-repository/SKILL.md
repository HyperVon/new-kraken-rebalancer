---
name: exposed-repository
description: >-
  Create or modify JetBrains Exposed ORM repository implementations and table
  schemas following project patterns — safeTransaction, cascade deletes,
  primary-key targeting, and upserts. Use when adding or changing Exposed
  tables, repository impls under repository/, SQLite persistence, or
  transaction helpers.
---

# Exposed Repository Patterns

When creating or modifying Exposed ORM repository implementations and table schemas, follow these patterns exactly.

## Exposed 1.x Packages

This project uses Exposed **1.4.0**. Prefer these import roots:

- Table / column / operator APIs: `org.jetbrains.exposed.v1.core.*` (e.g. `Table`, `eq`, `and`, `inList`)
- JDBC query / DML APIs: `org.jetbrains.exposed.v1.jdbc.*` (e.g. `Database`, `selectAll`, `insert`, `update`, `deleteWhere`)
- Transactions: `org.jetbrains.exposed.v1.jdbc.transactions.transaction` and `JdbcTransaction` receivers
- Schema metadata caches: `org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata`

Do **not** use the legacy `org.jetbrains.exposed.sql.*` packages.

## Table & Schema Definitions

Define tables extending `Table` (this repo does **not** use `LongIdTable`) with an explicit auto-incrementing integer id and explicit precision / foreign key cascade rules. Use the real schema in `backend/src/main/kotlin/com/gemini/krakenbot/repository/table/TradeTable.kt` as the canonical reference:

```kotlin
object TradeTable : Table("trades") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val pair = varchar("pair", 16)
    val side = varchar("side", 4)
    val volume = decimal("volume", 24, 8)
    val usdAmount = decimal("usd_amount", 18, 2)
    val price = decimal("price", 24, 8)
    val fee = decimal("fee", 18, 4)

    override val primaryKey = PrimaryKey(id)
}
```

- **Cryptocurrency Amounts**: Use `decimal("column_name", 24, 8)` for crypto balances and trade volumes (precision `>= 24`, scale `8`).
- **USD Valuations**: Use `decimal("column_name", 18, 2)` for fiat totals and valuations; fees use `decimal("column_name", 18, 4)`.
- **Foreign Keys**: Always specify `onDelete = ReferenceOption.CASCADE` on foreign key references.
- **Primary keys**: `Table` with an explicit `integer("id").autoIncrement()` + `override val primaryKey = PrimaryKey(id)` — do not use `LongIdTable`.

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

### Transaction helpers (`repository/impl/RepositoryUtils.kt`)

- **Writes:** `database.safeTransactionIO(log, message) { … }` — combines
  `withContext(Dispatchers.IO)` with `safeTransaction`.
- **Reads:** `database.readTransactionIO { … }` — plain `transaction(database)` on IO.
- `safeTransaction` wraps non-`IOException` failures as `IOException`; raw
  `IOException` is rethrown.
- Anti-pattern: `transaction { }` on `Dispatchers.Default` inside a suspend
  repository method.

```kotlin
override suspend fun save(trade: TradeRecord) =
    database.safeTransactionIO(log, "Failed to save trade to database") {
        TradeTable.insert {
            it[timestamp] = trade.timestamp.toEpochMilli()
            it[pair] = trade.pair
            it[side] = trade.side.name
            it[volume] = trade.volume
            it[usdAmount] = trade.usdAmount
        }
    }

override suspend fun loadAll(): List<TradeRecord> =
    database.readTransactionIO {
        TradeTable.selectAll()
            .orderBy(TradeTable.timestamp, SortOrder.DESC)
            .map { row -> buildTradeFromRow(row) }
    }
```

### In-memory SQLite keepalive

- `DatabaseConfig`: `:memory:` becomes
  `jdbc:sqlite:file:<uuid>?mode=memory&cache=shared&foreign_keys=true`, plus a
  shutdown-hook keepalive `Connection` per URL.
- Tests must use `:memory:` (or that shared URL) — never a file DB.
- Schema boot: `createStatements` + `addMissingColumnsStatements` + versioned
  migration steps + `checkMappingConsistence` in one transaction (Exposed 1.x
  — no deprecated `createMissingTablesAndColumns`). File-backed migrations
  receive a pre-migration backup; legacy submission guards migrate into the
  `order_intents` journal while the old trade column is retained until resolution.

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

`updateTrade` specifics:

- Default: `TradeTable.update({ TradeTable.id eq oldTrade.id })`.
- Fallback **only** when `oldTrade.id == null`: match timestamp + pair +
  normalized side + volume.
- Persist sides via `OrderSide.normalize()`.
- `getLatestTradeTime()` filters `dryRun = false` — coordinates with the sync
  watermark.

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

## Co-located Table Mappers (`toModel` & `applyTo`)

Define entity mapping methods directly on `Table` singleton objects:

- `toModel(row: ResultRow): Model`
- `applyTo(builder: UpdateBuilder<*>, model: Model)`

This couples the SQL column definitions directly with model mapping logic, eliminates repetitive `Table.` prefix boilerplate in repository implementations, and enables clean functional references like `.map(TradeTable::toModel)`:

```kotlin
// In Table definition:
object TradeTable : Table("trades") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val pair = varchar("pair", 16)

    fun toModel(row: ResultRow): TradeRecord = TradeRecord(
        timestamp = Instant.ofEpochMilli(row[timestamp]),
        pair = row[pair],
    )

    fun applyTo(builder: UpdateBuilder<*>, trade: TradeRecord) {
        builder[timestamp] = trade.timestamp.toEpochMilli()
        builder[pair] = trade.pair
    }
}

// In Repository:
TradeTable.insert { TradeTable.applyTo(it, trade) }
TradeTable.selectAll().map(TradeTable::toModel)
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

val downsampledIds = if (allIds.size <= MAX_SNAPSHOT_POINTS) {
    allIds
} else {
    List(MAX_SNAPSHOT_POINTS) { sampleIndex ->
        val sourceIndex =
            (sampleIndex.toLong() * allIds.lastIndex / (MAX_SNAPSHOT_POINTS - 1)).toInt()
        allIds[sourceIndex]
    }
}
```

## Checklist

Before submitting repository code, verify:

- [ ] Write/read operations use `safeTransactionIO` / `readTransactionIO`
- [ ] Deletes cascade children before parents
- [ ] Updates target by primary key ID
- [ ] `BigDecimal` columns use proper scale (24, 8 for crypto; 18, 2 for USD; 18, 4 for fees)
- [ ] Null-safe defaults (`BigDecimal.ZERO`, `0L`) for aggregate results
- [ ] No FQNs — all Exposed types imported at the top
