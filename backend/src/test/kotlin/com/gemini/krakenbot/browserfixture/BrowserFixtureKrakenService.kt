package com.gemini.krakenbot.browserfixture

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.BalanceRead
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectKrakenEvidenceService
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectTickerRequest
import com.gemini.krakenbot.service.DirectValueEvidence
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.TickerRead
import java.time.Instant

/** Gives the synthetic account fixture the same timestamped evidence surface as the live adapter. */
internal class BrowserFixtureKrakenService(private val delegate: FakeKrakenService) :
    KrakenService by delegate,
    DirectKrakenEvidenceService {
    override suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)

    override suspend fun readBalancesWithCapture(): BalanceRead {
        val requestStartedAt = Instant.now()
        val balances = delegate.getBalances()
        val responseEndedAt = Instant.now()
        return BalanceRead(
            balances = balances,
            capture = DirectBalanceCapture(
                requestStartedAt = requestStartedAt,
                responseEndedAt = responseEndedAt,
                resultShapeValid = true,
                valuesByAssetId = balances.mapValues { (_, amount) ->
                    DirectValueEvidence(
                        rawValue = amount.toPlainString(),
                        value = amount,
                        status = if (amount.signum() < 0) DirectEvidenceStatus.NEGATIVE else DirectEvidenceStatus.VALID,
                    )
                },
            ),
        )
    }

    override suspend fun readTickerPricesWithCapture(requests: List<DirectTickerRequest>): TickerRead {
        val requestStartedAt = Instant.now()
        val prices = if (requests.isEmpty()) {
            emptyMap()
        } else {
            delegate.getTickerPrices(requests.joinToString(",") { it.pair })
        }
        val responseEndedAt = Instant.now()
        val marks = requests.associate { request ->
            val candidates = prices.filterKeys { Asset.matchesUsdQuotedPair(it, request.symbol) }
            val price = candidates.values.singleOrNull()
            val status = when {
                candidates.isEmpty() -> DirectEvidenceStatus.MISSING
                candidates.size > 1 -> DirectEvidenceStatus.AMBIGUOUS
                price?.signum()?.let { it <= 0 } == true -> DirectEvidenceStatus.NON_POSITIVE
                else -> DirectEvidenceStatus.VALID
            }
            val candidateValues = candidates.mapValues { (_, value) ->
                DirectValueEvidence(
                    rawValue = value.toPlainString(),
                    value = value,
                    status = if (value.signum() <= 0) DirectEvidenceStatus.NON_POSITIVE else DirectEvidenceStatus.VALID,
                )
            }
            request.symbol to DirectTickerMark(
                symbol = request.symbol,
                requestedPair = request.pair,
                responsePair = candidates.keys.singleOrNull(),
                candidateResponsePairs = candidates.keys.toList(),
                rawPrice = price?.toPlainString(),
                price = price,
                status = status,
                candidateValuesByPair = candidateValues,
            )
        }
        return TickerRead(
            prices = prices,
            capture = DirectTickerCapture(
                requestStartedAt = requestStartedAt,
                responseEndedAt = responseEndedAt,
                resultShapeValid = true,
                marksBySymbol = marks,
            ),
        )
    }
}
