package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.service.BoundedTradeHistoryService
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.toCryptoScale
import com.gemini.krakenbot.util.toUsdScale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom

class SimulatedKrakenService(private val configService: ConfigService) :
    KrakenService,
    BoundedTradeHistoryService {
    private val log = LoggerFactory.getLogger(SimulatedKrakenService::class.java)

    private val balances = ConcurrentHashMap<String, BigDecimal>()
    private val simulatedPrices = ConcurrentHashMap<String, BigDecimal>()
    private val simulatedTrades = CopyOnWriteArrayList<TradeRecord>()
    private val simulatedLedgerEntries = CopyOnWriteArrayList<LedgerEvent>()
    private val orderMutex = Mutex()
    private var historicalTradesSeeded = false
    private var historicalLedgersSeeded = false
    private var lastTradeHistoryTotalCount = 0
    private var lastLedgerCount = 0

    init {
        log.info("Initialized SimulatedKrakenService")
    }

    private val initLock = Any()

    // Serializes first-touch init and one-shot trade seeding across concurrent emulator calls.
    // Uses a private lock (not the instance monitor) and must remain non-suspending so callers
    // that already hold `orderMutex` do not block a thread inside a coroutine.
    private fun initializeMissingBalancesAndPrices(): Unit = synchronized(initLock) {
        val allocations = configService.getConfig().allocations
        val missingSymbols = allocations.filter { !balances.containsKey(it.symbol.value.uppercase()) }

        if (missingSymbols.isNotEmpty()) {
            log.info(
                "SimulatedKrakenService: initializing {} missing portfolio asset(s)...",
                missingSymbols.size,
            )
        }

        for ((symbol) in allocations) {
            val symbolU = symbol.value.uppercase()
            val basePrice = SimulationDefaults.INITIAL_PRICES[symbolU] ?: SimulationDefaults.DEFAULT_PRICE
            simulatedPrices.putIfAbsent(symbolU, basePrice.toCryptoScale())
        }
        simulatedPrices.putIfAbsent(Asset.USD, BigDecimal.ONE)

        // Drift new balances ±25% off target so the emulator starts needing rebalances.
        val totalSimulatedValueUSD = SimulationDefaults.TOTAL_PORTFOLIO_VALUE_USD
        val random = ThreadLocalRandom.current()

        for ((symbol, targetPercent) in missingSymbols) {
            val symbolU = symbol.value.uppercase()
            val targetUSDValue =
                PortfolioCalculations.calculateTargetValue(
                    BigDecimal.valueOf(targetPercent),
                    totalSimulatedValueUSD,
                )

            val driftFactor = BigDecimal.valueOf(0.75 + random.nextDouble() * 0.50)
            val driftedUSDValue = targetUSDValue.multiply(driftFactor).toUsdScale()

            if (symbolU == Asset.USD) {
                balances[Asset.USD] = driftedUSDValue
            } else {
                val price = simulatedPrices.getValue(symbolU)
                balances[symbolU] =
                    driftedUSDValue.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
            }
        }

        // Seed demo history once when both sides of a USD-quoted fill can be represented.
        if (
            !historicalTradesSeeded &&
            allocations.any { !it.symbol.isUsd } &&
            allocations.any { it.symbol.isUsd }
        ) {
            seedSimulatedTrades()
            historicalTradesSeeded = true
            seedSimulatedLedgerEntries()
            historicalLedgersSeeded = true
        }
    }

    // Seeds staking ledger history so the rewards panel is populated in simulation
    // mode. Entries spread across the same ~15-day window as seeded snapshots and
    // trades, so the cumulative rewards line grows realistically over any range.
    private fun seedSimulatedLedgerEntries() {
        val now = Instant.now()
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.isUsd }
        if (nonUsd.isEmpty()) return

        nonUsd.forEachIndexed { assetIndex, alloc ->
            val symbol = alloc.symbol.value.uppercase()
            val price =
                simulatedPrices[symbol]
                    ?: (SimulationDefaults.INITIAL_PRICES[symbol] ?: SimulationDefaults.DEFAULT_PRICE)
            repeat(5) { eventIndex ->
                val hoursAgo = 14L + eventIndex * 72L + assetIndex.toLong()
                val rewardUsd = BigDecimal.valueOf(25L + eventIndex * 6L)
                simulatedLedgerEntries.add(
                    LedgerEvent(
                        ledgerId = "SIM-SEED-LEDGER-$assetIndex-$eventIndex",
                        time = now.minus(hoursAgo, ChronoUnit.HOURS),
                        type = LedgerEvent.TYPE_STAKING,
                        subtype = "reward",
                        aclass = "currency",
                        asset = symbol,
                        amount = rewardUsd.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP),
                    ),
                )
            }
        }
    }

    private fun seedSimulatedTrades() {
        val now = Instant.now()
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.isUsd }
        if (nonUsd.isEmpty()) return

        // Seven paired rebalances over the last five days. Each pair returns the
        // asset quantity to its starting level while retaining realistic fee and
        // execution-price effects in USD, so seeded snapshots can reconcile the
        // same fills without inventing deposits or withdrawals.
        repeat(7) { index ->
            val alloc = nonUsd[index % nonUsd.size]
            val symbol = alloc.symbol.value.uppercase()
            val pair = Asset.tradingPair(symbol)
            val basePrice = SimulationDefaults.INITIAL_PRICES[symbol] ?: SimulationDefaults.DEFAULT_PRICE
            val firstPrice = basePrice.multiply(BigDecimal.valueOf(0.94 + index * 0.012)).toCryptoScale()
            val returnFactor = if (index % 3 == 0) {
                1.035
            } else if (index % 3 == 1) {
                0.978
            } else {
                1.014
            }
            val secondPrice = firstPrice.multiply(BigDecimal.valueOf(returnFactor)).toCryptoScale()
            val targetUsd = BigDecimal.valueOf(800L + index * 175L).toUsdScale()
            val volume = targetUsd.divide(firstPrice, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
            val firstSide = if (index % 2 == 0) OrderSide.BUY else OrderSide.SELL
            val secondSide = if (firstSide == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
            // Anchored to SimulationDefaults.SEED_LATEST_TRADE_HOURS_AGO so the final pair's
            // second leg always lands that many hours before `now`; TradeHistorySnapshotStore
            // relies on this to align its snapshot grid. Equivalent to 114L - index * 16L.
            val firstHoursAgo =
                SimulationDefaults.SEED_LATEST_TRADE_HOURS_AGO + 6L + (6 - index) * 16L

            simulatedTrades += seededTrade(
                timestamp = now.minus(firstHoursAgo, ChronoUnit.HOURS),
                pair = pair,
                side = firstSide,
                symbol = symbol,
                volume = volume,
                price = firstPrice,
                seedId = "${index + 1}-1",
            )
            simulatedTrades += seededTrade(
                timestamp = now.minus(firstHoursAgo - 6L, ChronoUnit.HOURS),
                pair = pair,
                side = secondSide,
                symbol = symbol,
                volume = volume,
                price = secondPrice,
                seedId = "${index + 1}-2",
            )
        }
        val sorted = simulatedTrades.sortedBy { it.timestamp }
        simulatedTrades.clear()
        simulatedTrades.addAll(sorted)
    }

    private fun seededTrade(
        timestamp: Instant,
        pair: String,
        side: OrderSide,
        symbol: String,
        volume: BigDecimal,
        price: BigDecimal,
        seedId: String,
    ): TradeRecord {
        val usdAmount = volume.multiply(price).toUsdScale()
        return TradeRecord(
            timestamp = timestamp,
            pair = pair,
            side = side.name,
            symbol = symbol,
            volume = volume,
            usdAmount = usdAmount,
            success = true,
            dryRun = false,
            price = price,
            fee = usdAmount.multiply(SEED_FEE_RATE).setScale(PrecisionConstants.SCALE_FEE, RoundingMode.HALF_UP),
            source = TradeSource.API_FILL,
            orderTxid = "$SEED_ORDER_TXID_PREFIX$seedId",
            tradeId = "$SEED_TRADE_ID_PREFIX$seedId",
        )
    }

    private fun fluctuatePrices() {
        val random = ThreadLocalRandom.current()
        for ((symbol, currentPrice) in simulatedPrices) {
            if (symbol == Asset.USD) continue
            // Random walk between -0.6% and +0.6%
            val changeFactor = BigDecimal.ONE.add(BigDecimal.valueOf((random.nextDouble() - 0.5) * 0.012))
            simulatedPrices[symbol] = currentPrice.multiply(changeFactor).toCryptoScale()
        }
    }

    override suspend fun getBalances(): RawBalances = orderMutex.withLock {
        initializeMissingBalancesAndPrices()
        balances.toMap()
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        initializeMissingBalancesAndPrices()
        fluctuatePrices()

        val results = mutableMapOf<String, BigDecimal>()
        val pairList = pairs.split(",")
        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        for (pair in pairList) {
            val symbol = Asset.fromTradingPair(pair, allocations) ?: pair
            val price = simulatedPrices[symbol] ?: BigDecimal.TEN
            results[pair] = price
        }
        return results
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean?,
        clOrdId: String?,
    ): OrderResult {
        initializeMissingBalancesAndPrices()

        val normalizedVolumeForError = volume.toCryptoScale()
        if (!type.equals(OrderType.MARKET.apiValue, ignoreCase = true)) {
            val error = "Unsupported order type in emulator: $type (only ${OrderType.MARKET.apiValue} is supported)"
            log.warn("[EMULATOR] $error")
            return OrderResult(
                success = false,
                pair = pair,
                side = side,
                volume = normalizedVolumeForError,
                errorMessage = error,
            )
        }
        val orderSide = OrderSide.entries.firstOrNull { it.apiValue.equals(side, ignoreCase = true) }
        if (orderSide == null) {
            val error = "Unsupported order side in emulator: $side"
            log.warn("[EMULATOR] $error")
            return OrderResult(
                success = false,
                pair = pair,
                side = side,
                volume = normalizedVolumeForError,
                errorMessage = error,
            )
        }

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val symbol = Asset.fromTradingPair(pair, allocations) ?: pair
        val price = simulatedPrices[symbol] ?: BigDecimal.TEN
        val normalizedVolume = volume.toCryptoScale()
        val usdAmount = normalizedVolume.multiply(price).toUsdScale()
        val fee = usdAmount.multiply(SEED_FEE_RATE).setScale(PrecisionConstants.SCALE_FEE, RoundingMode.HALF_UP)

        if ((dryRun ?: configService.getConfig().settings.dryRun)) {
            log.info("[EMULATOR DRY RUN] Order would execute successfully cl_ord_id=$clOrdId")
            return OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = normalizedVolume,
                dryRun = true,
            )
        }

        log.info(
            "[EMULATOR] Executing $side order on $pair, volume: $normalizedVolume, " +
                "calculated price: $price ($$usdAmount) cl_ord_id=$clOrdId",
        )

        return orderMutex.withLock {
            val usdBalance = balances[Asset.USD] ?: BigDecimal.ZERO
            val tokenBalance = balances[symbol] ?: BigDecimal.ZERO

            if (orderSide == OrderSide.BUY) {
                if (usdBalance < usdAmount.add(fee)) {
                    val error =
                        "Insufficient USD funds in emulator balance: needed ${usdAmount.add(fee)}, had $usdBalance"
                    log.warn("[EMULATOR] $error")
                    return@withLock OrderResult(
                        success = false,
                        pair = pair,
                        side = side,
                        volume = normalizedVolume,
                        errorMessage = error,
                    )
                }
                balances[Asset.USD] = usdBalance.subtract(usdAmount).subtract(fee).toUsdScale()
                balances[symbol] = tokenBalance.add(normalizedVolume).toCryptoScale()
            } else {
                if (tokenBalance < normalizedVolume) {
                    val error =
                        "Insufficient $symbol funds in emulator balance: needed $normalizedVolume, had $tokenBalance"
                    log.warn("[EMULATOR] $error")
                    return@withLock OrderResult(
                        success = false,
                        pair = pair,
                        side = side,
                        volume = normalizedVolume,
                        errorMessage = error,
                    )
                }
                balances[symbol] = tokenBalance.subtract(normalizedVolume).toCryptoScale()
                balances[Asset.USD] = usdBalance.add(usdAmount).subtract(fee).toUsdScale()
            }

            val orderTxid = "$SIM_ORDER_TXID_PREFIX${System.nanoTime()}"
            val trade =
                TradeRecord(
                    timestamp = Instant.now(),
                    pair = pair,
                    side = side.uppercase(),
                    symbol = symbol,
                    volume = normalizedVolume,
                    usdAmount = usdAmount,
                    success = true,
                    dryRun = false,
                    price = price.toCryptoScale(),
                    fee = fee,
                    source = TradeSource.API_FILL,
                    orderTxid = orderTxid,
                )
            simulatedTrades.add(trade)

            OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = normalizedVolume,
                orderTxid = orderTxid,
            )
        }
    }

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> =
        getTradeHistoryUntil(startSec, offset, null)

    override suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> {
        initializeMissingBalancesAndPrices()

        var filtered =
            if (startSec != null) {
                val startInstant = Instant.ofEpochSecond(startSec)
                simulatedTrades.filter { !it.timestamp.isBefore(startInstant) }
            } else {
                simulatedTrades
            }

        if (endSec != null) {
            val endInstant = Instant.ofEpochSecond(endSec)
            filtered = filtered.filter { !it.timestamp.isAfter(endInstant) }
        }

        filtered = filtered.sortedByDescending { it.timestamp }
        lastTradeHistoryTotalCount = filtered.size

        // Kraken returns at most 50 records per page (newest first). An offset
        // at/beyond the result size therefore yields an empty page, not the whole history.
        return filtered
            .drop(offset?.coerceAtLeast(0) ?: 0)
            .take(KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE)
    }

    override fun getLastTradeHistoryTotalCount(): Int = lastTradeHistoryTotalCount

    override suspend fun getLedgers(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
        types: Set<String>?,
    ): List<LedgerEvent> {
        initializeMissingBalancesAndPrices()

        var filtered =
            if (types != null) {
                simulatedLedgerEntries.filter { it.type in types }
            } else {
                simulatedLedgerEntries
            }

        if (startSec != null) {
            val startInstant = Instant.ofEpochSecond(startSec)
            filtered = filtered.filter { !it.time.isBefore(startInstant) }
        }

        if (endSec != null) {
            val endInstant = Instant.ofEpochSecond(endSec)
            filtered = filtered.filter { !it.time.isAfter(endInstant) }
        }

        filtered = filtered.sortedByDescending { it.time }
        lastLedgerCount = filtered.size

        // Mirrors the private Ledgers endpoint: at most 50 entries per page (newest
        // first); an offset at/beyond the result size yields an empty page.
        return filtered
            .drop(offset?.coerceAtLeast(0) ?: 0)
            .take(KrakenApiConstants.LEDGER_PAGE_SIZE)
    }

    override fun getLastLedgerTotalCount(): Int = lastLedgerCount

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> = emptyList()

    /** Offline emulator has no network rate limiter; returns 0.0. */
    override suspend fun getApiCallCounter(): Double = 0.0

    private companion object {
        const val SEED_ORDER_TXID_PREFIX = "SIM-SEED-"
        const val SEED_TRADE_ID_PREFIX = "SIM-SEED-FILL-"
        const val SIM_ORDER_TXID_PREFIX = "SIM-"
        val SEED_FEE_RATE: BigDecimal = BigDecimal("0.0026")
    }
}
