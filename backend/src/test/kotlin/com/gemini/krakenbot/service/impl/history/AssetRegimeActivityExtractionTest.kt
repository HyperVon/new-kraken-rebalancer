package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

/**
 * Behavioral activity extraction: terminal-exit detection, baseline seeding, and materiality.
 *
 * These are the parts of the inferred-regime pipeline most able to regress silently, because a
 * wrong exit makes a real removal look transient, and a wrong establishment makes dust look like a
 * regime member.
 */
class AssetRegimeActivityExtractionTest : StringSpec() {

    private val t0: Instant = Instant.parse("2025-12-05T17:00:00Z")

    private fun snapshot(assets: Map<String, Triple<String, String, String>>) = PortfolioSnapshot(
        timestamp = t0,
        totalValueUSD = BigDecimal("1000.00"),
        assets = assets.mapValues { (symbol, r) ->
            PortfolioSnapshot.AssetSnapshot(
                symbol = symbol,
                balance = BigDecimal(r.first),
                price = BigDecimal(r.second),
                valueUSD = BigDecimal(r.third),
                targetPercent = BigDecimal.ZERO,
                currentPercent = BigDecimal.ZERO,
                deviationPercent = BigDecimal.ZERO,
                deviationUSD = BigDecimal.ZERO,
            )
        },
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal.ZERO,
    )

    private fun ledger(id: String, time: Instant, asset: String, balance: String) = LedgerEvent(
        ledgerId = id,
        time = time,
        type = "trade",
        asset = asset,
        amount = BigDecimal.ZERO,
        balance = BigDecimal(balance),
        hasAuthoritativeBalance = true,
    )

    private fun trade(time: Instant, symbol: String, success: Boolean = true, dryRun: Boolean = false) = TradeRecord(
        timestamp = time,
        pair = "${symbol}USD",
        side = "BUY",
        symbol = symbol,
        volume = BigDecimal.ONE,
        usdAmount = BigDecimal.TEN,
        success = success,
        dryRun = dryRun,
    )

    private fun activities(
        baseline: PortfolioSnapshot,
        trades: List<TradeRecord> = emptyList(),
        ledgers: List<LedgerEvent> = emptyList(),
        scope: Set<String> = setOf("AAA", "BBB", "CCC", "USD"),
    ) = RebalancerComparisonCalculator.buildActivitiesForTest(
        trades = trades,
        ledgerEvents = ledgers,
        baseline = baseline,
        comparisonAssetSymbols = scope,
    ).associateBy { it.symbol }

    init {
        "a baseline holding fully sold by the first ledger row still shows its exit" {
            // The ledger only records the post-trade zero, so without baseline seeding the exit
            // would be invisible and the flagship removal would never be inferred.
            val baseline = snapshot(
                mapOf("AAA" to Triple("100.0", "10.00", "1000.00")),
            )
            val result = activities(
                baseline = baseline,
                trades = listOf(trade(t0.plusSeconds(27), "AAA")),
                ledgers = listOf(ledger("l1", t0.plusSeconds(27), "AAA", "0")),
            )

            val aaa = result.getValue("AAA")
            aaa.materiallyPresentAtBaseline shouldBe true
            aaa.fullExitAt shouldBe t0.plusSeconds(27)
        }

        "a transient flip to zero mid-holding is not the terminal exit" {
            // The 20-second round trip must not become the exit; the real terminal exit is the last zero.
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                ledgers = listOf(
                    ledger("l1", t0.plusSeconds(20), "AAA", "5.0"),
                    ledger("l2", t0.plusSeconds(40), "AAA", "0"),
                    ledger("l3", t0.plusSeconds(60), "AAA", "7.0"),
                    ledger("l4", t0.plusSeconds(100), "AAA", "0"),
                ),
            )

            result.getValue("AAA").fullExitAt shouldBe t0.plusSeconds(100)
        }

        "a baseline dust crumb is not treated as regime membership" {
            // $0.24 of BTC is far below the smallest order the strategy would place, so it must not
            // count as a baseline holding or an epoch-zero addition.
            val baseline = snapshot(
                mapOf("BTC" to Triple("0.00000272", "88000.00", "0.24")),
            )
            val result = activities(
                baseline = baseline,
                scope = setOf("BTC", "USD"),
            )

            result.getValue("BTC").materiallyPresentAtBaseline shouldBe false
        }

        "a material baseline holding is treated as regime membership" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("100.0", "10.00", "1000.00"), "USD" to Triple("500.0", "1.00", "500.0")),
            )
            val result = activities(baseline = baseline, scope = setOf("AAA", "USD"))

            result.getValue("AAA").materiallyPresentAtBaseline shouldBe true
            result.getValue("USD").materiallyPresentAtBaseline shouldBe true
        }

        "an asset that never exits reports no exit" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                ledgers = listOf(ledger("l1", t0.plusSeconds(60), "AAA", "3.0")),
            )

            result.getValue("AAA").fullExitAt shouldBe null
        }

        "ledger rows without an authoritative balance are ignored" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                ledgers = listOf(
                    ledger("l1", t0.plusSeconds(60), "AAA", "4.0").copy(hasAuthoritativeBalance = false),
                    ledger("l2", t0.plusSeconds(90), "AAA", "0"),
                ),
            )

            // The synthetic row carried no authoritative balance, so it cannot establish a holding.
            // With no authoritative positive balance there is nothing to exit from either.
            result.getValue("AAA").fullExitAt shouldBe null
            result.getValue("AAA").establishedAt shouldBe null
        }

        "an asset whose price is unknown at the baseline is not materially present" {
            val baseline = snapshot(
                mapOf("UNKNOWN" to Triple("100.0", "0.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                scope = setOf("UNKNOWN", "USD"),
            )

            result.getValue("UNKNOWN").materiallyPresentAtBaseline shouldBe false
        }

        "an in-scope symbol with no fills and no balances has no fills or establishment" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                scope = setOf("AAA", "SILENT", "USD"),
            )

            // A tracked symbol with no observed activity at all must not be invented into a member.
            val silent = result.getValue("SILENT")
            silent.fillCount shouldBe 0
            silent.firstFill shouldBe null
            silent.lastFill shouldBe null
            silent.establishedAt shouldBe null
            silent.fullExitAt shouldBe null
        }

        "an asset with both fills and an authoritative exit reports both" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                trades = listOf(trade(t0, "AAA"), trade(t0.plusSeconds(60), "AAA")),
                ledgers = listOf(
                    ledger("l1", t0.plusSeconds(30), "AAA", "4.0"),
                    ledger("l2", t0.plusSeconds(90), "AAA", "0"),
                ),
            )

            val aaa = result.getValue("AAA")
            aaa.establishedAt shouldBe t0.plusSeconds(30)
            aaa.fullExitAt shouldBe t0.plusSeconds(90)
            aaa.firstFill shouldBe t0
            aaa.lastFill shouldBe t0.plusSeconds(60)
        }

        "a still-held asset with fills has no exit and stays present through its last observation" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                trades = listOf(trade(t0, "AAA"), trade(t0.plusSeconds(120), "AAA")),
                ledgers = listOf(
                    ledger("l1", t0.plusSeconds(30), "AAA", "4.0"),
                    ledger("l2", t0.plusSeconds(90), "AAA", "6.0"),
                ),
            )

            val aaa = result.getValue("AAA")
            aaa.establishedAt shouldBe t0.plusSeconds(30)
            aaa.fullExitAt shouldBe null
            aaa.economicallyPresentSpanMillis shouldBe 60_000L
        }

        "ledger rows for assets outside the comparison scope are ignored" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                ledgers = listOf(ledger("out", t0.plusSeconds(30), "SECURITY", "5.0")),
                scope = setOf("AAA", "USD"),
            )

            result.containsKey("SECURITY") shouldBe false
        }

        "failed and dry-run trades do not count or extend the successful live fill span" {
            val baseline = snapshot(
                mapOf("AAA" to Triple("0.0", "10.00", "0.00")),
            )
            val result = activities(
                baseline = baseline,
                trades = listOf(
                    trade(t0, "AAA", success = false),
                    trade(t0.plusSeconds(60), "AAA"),
                    trade(t0.plusSeconds(120), "AAA"),
                    trade(t0.plusSeconds(180), "AAA", dryRun = true),
                ),
            )

            val aaa = result.getValue("AAA")
            aaa.fillCount shouldBe 2
            aaa.firstFill shouldBe t0.plusSeconds(60)
            aaa.lastFill shouldBe t0.plusSeconds(120)
        }

        "failed and dry-run records cannot create persistence despite sustained balance evidence" {
            val spanSeconds = 90L * 86_400L
            val invalidTrades = (0 until ConfigurationRegimeInference.MIN_FILLS + 1).map { index ->
                val time = t0.plusSeconds(index.toLong() * spanSeconds / ConfigurationRegimeInference.MIN_FILLS)
                if (index % 2 == 0) {
                    trade(time, "AAA", success = false)
                } else {
                    trade(time, "AAA", dryRun = true)
                }
            }
            val result = activities(
                baseline = snapshot(mapOf("AAA" to Triple("0.0", "10.00", "0.00"))),
                trades = invalidTrades,
                ledgers = listOf(
                    ledger("start", t0, "AAA", "10.0"),
                    ledger("end", t0.plusSeconds(spanSeconds), "AAA", "10.0"),
                ),
                scope = setOf("AAA", "USD"),
            )
            val aaa = result.getValue("AAA")

            aaa.fillCount shouldBe 0
            aaa.firstFill shouldBe null
            aaa.lastFill shouldBe null
            aaa.economicallyPresentSpanMillis shouldBe spanSeconds * 1_000L
            ConfigurationRegimeInference.infer(result.values) shouldBe emptyList()
        }

        "enough successful live fills still establish a persistent member" {
            val spanSeconds = 90L * 86_400L
            val liveFills = (0 until ConfigurationRegimeInference.MIN_FILLS).map { index ->
                val time = t0.plusSeconds(
                    index.toLong() * spanSeconds / (ConfigurationRegimeInference.MIN_FILLS - 1),
                )
                trade(time, "AAA")
            }
            val result = activities(
                baseline = snapshot(mapOf("AAA" to Triple("0.0", "10.00", "0.00"))),
                trades = liveFills,
                ledgers = listOf(
                    ledger("start", t0, "AAA", "10.0"),
                    ledger("end", t0.plusSeconds(spanSeconds), "AAA", "10.0"),
                ),
                scope = setOf("AAA", "USD"),
            )
            val aaa = result.getValue("AAA")

            aaa.fillCount shouldBe ConfigurationRegimeInference.MIN_FILLS
            aaa.firstFill shouldBe t0
            aaa.lastFill shouldBe t0.plusSeconds(spanSeconds)
            ConfigurationRegimeInference.infer(result.values).single().additions shouldBe setOf("AAA")
        }
    }
}
