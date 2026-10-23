package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.domain.toCryptoScale
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ExchangeTradeFill
import com.gemini.krakenbot.model.ExchangeTradePage
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom

class SimulatedKrakenService(private val configService: ConfigService) : KrakenService {
    private val log = LoggerFactory.getLogger(SimulatedKrakenService::class.java)

    private val balances = ConcurrentHashMap<String, BigDecimal>()
    private val simulatedPrices = ConcurrentHashMap<String, BigDecimal>()
    private val simulatedTrades = CopyOnWriteArrayList<TradeRecord>()
    private val orderMutex = Mutex()

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

    override suspend fun getTickerPrices(pairs: String): RawPrices = orderMutex.withLock {
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
        results
    }

    override suspend fun getAssetMetadata(): List<KrakenAssetMetadata> = SIMULATED_ASSET_METADATA

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean,
        clOrdId: String?,
    ): OrderResult = orderMutex.withLock {
        // Initialization, pricing, balance validation, and mutation share one state lock. This
        // keeps concurrent simulated orders from calculating against the same pre-order balance.
        initializeMissingBalancesAndPrices()

        val normalizedVolumeForError = volume.toCryptoScale()
        if (!type.equals(OrderType.MARKET.apiValue, ignoreCase = true)) {
            val error = "Unsupported order type in emulator: $type (only ${OrderType.MARKET.apiValue} is supported)"
            log.warn("[EMULATOR] $error")
            return@withLock OrderResult(
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
            return@withLock OrderResult(
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
        val fee = usdAmount.multiply(SIMULATED_FEE_RATE).setScale(PrecisionConstants.SCALE_FEE, RoundingMode.HALF_UP)

        if (dryRun) {
            log.info("[EMULATOR DRY RUN] Order would execute successfully cl_ord_id=$clOrdId")
            return@withLock OrderResult(
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
                tradeId = "$SIM_TRADE_ID_PREFIX${System.nanoTime()}",
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

    override suspend fun getRecentTradePage(
        startSec: Long?,
        endSec: Long?,
        cursor: String?,
        limit: Int?,
    ): ExchangeTradePage = orderMutex.withLock {
        require(limit == null || limit in 1..MAX_TRADE_PAGE_LIMIT) {
            "Trade page limit must be between 1 and $MAX_TRADE_PAGE_LIMIT."
        }
        initializeMissingBalancesAndPrices()

        val filtered = simulatedTrades
            .asSequence()
            .filter { startSec == null || !it.timestamp.isBefore(Instant.ofEpochSecond(startSec)) }
            .filter { endSec == null || !it.timestamp.isAfter(Instant.ofEpochSecond(endSec)) }
            .sortedByDescending(TradeRecord::timestamp)
            .toList()
        val pageSize = limit ?: KrakenApiConstants.TRADE_PAGE_SIZE
        val slice = cursorPage(
            entries = filtered,
            requestedCursor = cursor,
            pageSize = pageSize,
            entryId = { it.tradeId },
        )
        val mapped = slice.entries.map(::toExchangeTrade)
        ExchangeTradePage(
            entries = mapped,
            nextCursor = slice.nextCursor,
            valid = slice.valid,
        )
    }

    private fun toExchangeTrade(trade: TradeRecord): ExchangeTradeFill {
        val pairSplit = Asset.splitTradingPair(trade.pair)
        return ExchangeTradeFill(
            tradeId = trade.tradeId.orEmpty(),
            orderTxid = trade.orderTxid,
            baseAsset = pairSplit?.base,
            quoteCurrency = pairSplit?.quote,
            // The simulator deducts its fee from USD cash, so its synthetic row has a known unit.
            feeCurrency = Asset.USD.takeIf { pairSplit?.quote == Asset.USD },
            type = trade.side.lowercase(),
            price = trade.price.toPlainString(),
            cost = trade.usdAmount.toPlainString(),
            fee = trade.fee.toPlainString(),
            volume = trade.volume.toPlainString(),
            hasValidTime = true,
            hasValidPrice = trade.price.signum() > 0,
            hasValidCost = trade.usdAmount.signum() >= 0,
            hasValidFee = trade.fee.signum() >= 0,
            hasValidVolume = trade.volume.signum() > 0,
            isValidRecord = !trade.tradeId.isNullOrBlank(),
        )
    }

    private class CursorSlice<T>(val entries: List<T>, val nextCursor: String?, val valid: Boolean)

    private fun <T> cursorPage(
        entries: List<T>,
        requestedCursor: String?,
        pageSize: Int,
        entryId: (T) -> String?,
    ): CursorSlice<T> {
        val startIndex = if (requestedCursor == null) {
            0
        } else {
            val cursorIndex = entries.indexOfFirst { entryId(it) == requestedCursor }
            if (cursorIndex < 0) return CursorSlice(emptyList(), null, false)
            cursorIndex + 1
        }
        val page = entries.drop(startIndex).take(pageSize)
        val hasMore = startIndex + page.size < entries.size
        val nextCursor = if (hasMore) page.lastOrNull()?.let(entryId)?.takeIf(String::isNotBlank) else null
        return CursorSlice(page, nextCursor, !hasMore || nextCursor != null)
    }

    /** Offline emulator has no network rate limiter; returns 0.0. */
    override suspend fun getApiCallCounter(): Double = 0.0

    private companion object {
        const val MAX_TRADE_PAGE_LIMIT = 100
        val SIMULATED_ASSET_METADATA =
            (SimulationDefaults.INITIAL_PRICES.keys + listOf("MORPHO", "XMR"))
                .map { KrakenAssetMetadata(it, KrakenApiConstants.ASSET_CLASS_CURRENCY) }
        const val SIM_ORDER_TXID_PREFIX = "SIM-"
        const val SIM_TRADE_ID_PREFIX = "SIM-FILL-"
        val SIMULATED_FEE_RATE: BigDecimal = BigDecimal("0.0026")
    }
}
