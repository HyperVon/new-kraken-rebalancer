package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.math.BigDecimal
import java.time.Instant

class AuthoritativeLedgerBalanceValidatorTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    init {
        "includes trade ledger rows in continuity validation" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("receive", 0, "receive", "100", "100"),
                    event("trade", 1, "trade", "-10", "89.9", fee = "0.1"),
                    event("reward", 2, "receive", "1", "90.9"),
                ),
            )

            result.isValid shouldBe true
            result.tradeCheckpointCount shouldBe 1
            result.validatedCheckpointCount shouldBe 3
        }

        "rejects malformed and impossible credit amounts before scope replay" {
            val malformed = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("malformed", 0, "receive", "0", "0", validAmount = false)),
            )
            malformed.isValid shouldBe false
            requireNotNull(malformed.failure).diagnostic shouldContain "invalid or malformed ledger amount"

            val negativeCredit = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("negative-credit", 0, "receive", "-1", "-1")),
            )
            negativeCredit.isValid shouldBe false
            requireNotNull(negativeCredit.failure).diagnostic shouldContain "invalid or malformed ledger amount"
        }

        "does not invent an observed balance for a malformed unobserved amount" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "malformed-unobserved",
                        0,
                        "receive",
                        "0",
                        "0",
                        authoritativeBalance = false,
                        validAmount = false,
                    ),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "observed=n/a"
        }

        "solves same-timestamp rows without relying on ledger-id order" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("before", 0, "receive", "100", "100"),
                    event("lexically-first", 1, "trade", "3", "105"),
                    event("lexically-last", 1, "trade", "2", "102"),
                ),
            )

            result.isValid shouldBe true
            result.sameTimestampCheckpointCount shouldBe 2
        }

        "deduplicates equivalent permutations in a same-timestamp checkpoint group" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("before-equivalent", 0, "receive", "100", "100"),
                    event("equivalent-a", 1, "receive", "0", "100"),
                    event("equivalent-b", 1, "receive", "0", "100"),
                ),
            )

            result.isValid shouldBe true
            result.sameTimestampCheckpointCount shouldBe 2
        }

        "treats same-timestamp internal transfer legs as one scope-independent group" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "10", "10"),
                    event(
                        "to-staking",
                        1,
                        "transfer",
                        "-6",
                        "4",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event(
                        "from-spot",
                        1,
                        "transfer",
                        "6",
                        "6",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event("staking-reward", 2, "staking", "0.1", "6.1"),
                ),
            )

            result.isValid shouldBe true
            result.scopeCount shouldBe 2
            result.resolvedScopes["to-staking"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
            result.resolvedScopes["from-spot"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING
        }

        "accepts a legacy four-decimal fee envelope derived from the stored fee" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "spot-transfer",
                        0,
                        "transfer",
                        "-70.09941494",
                        "70.09941494",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event(
                        "staking-transfer",
                        0,
                        "transfer",
                        "70.09941494",
                        "70.09941494",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event(
                        "staking-reward",
                        1,
                        "staking",
                        "0.04297118",
                        "70.12949477",
                        fee = "0.0129",
                    ),
                    event(
                        "staking-return-debit",
                        2,
                        "transfer",
                        "-0.03007118",
                        "70.09942359",
                        subtype = "stakingtospot",
                        refid = "staking-return",
                    ),
                    event(
                        "staking-return-credit",
                        2,
                        "transfer",
                        "0.03007118",
                        "70.12948612",
                        subtype = "stakingtospot",
                        refid = "staking-return",
                    ),
                ),
            )

            result.isValid shouldBe true
        }

        "rejects a high-precision fee mismatch outside the derived envelope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "spot-transfer",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event(
                        "staking-transfer",
                        0,
                        "transfer",
                        "1",
                        "1",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event(
                        "staking-reward",
                        1,
                        "staking",
                        "0.1",
                        "1.0999",
                        fee = "0.00000001",
                    ),
                ),
            )

            result.isValid shouldBe false
            val failure = requireNotNull(result.failure)
            failure.diagnostic shouldContain "expected="
            failure.diagnostic shouldContain "observed="
            failure.diagnostic shouldContain "delta="
            failure.diagnostic shouldNotContain "staking-reward"
        }

        "matches non-authoritative dust sweeps only when they close an existing scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("receive", 0, "receive", "1", "1", asset = "BABY"),
                    event(
                        "dust-sweep",
                        1,
                        "spend",
                        "-1",
                        "0",
                        asset = "BABY",
                        subtype = "dustsweeping",
                        authoritativeBalance = false,
                    ),
                ),
            )

            result.isValid shouldBe true
            result.nonAuthoritativeEventCount shouldBe 1
        }

        "starts an authoritative dust scope when no prior scope exists" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "initial-dust",
                        0,
                        "spend",
                        "-1",
                        "0",
                        subtype = "dustsweeping",
                    ),
                ),
            )

            result.isValid shouldBe true
            result.scopeCount shouldBe 1
        }

        "ignores a non-authoritative dust row without an established scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "unscoped-dust",
                        0,
                        "spend",
                        "-1",
                        "0",
                        subtype = "dustsweeping",
                        authoritativeBalance = false,
                    ),
                ),
            )

            result.isValid shouldBe true
            result.nonAuthoritativeEventCount shouldBe 1
        }

        "skips a non-authoritative staking row when multiple scopes exist" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "1", "1"),
                    event("opaque-staking", 1, "staking", "1", "1"),
                    event(
                        "unobserved-staking",
                        2,
                        "staking",
                        "0.1",
                        "0",
                        authoritativeBalance = false,
                    ),
                    event("spot-after", 3, "receive", "0", "1"),
                ),
            )

            result.isValid shouldBe true
            result.nonAuthoritativeEventCount shouldBe 1
        }

        "fails closed when a flexible sweep can match multiple wallet scopes" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("opaque-staking", 0, "staking", "1", "1"),
                    event("spot-refill", 1, "receive", "1", "1"),
                    event(
                        "ambiguous-sweep",
                        2,
                        "spend",
                        "-1",
                        "0",
                        subtype = "dustsweeping",
                        authoritativeBalance = false,
                    ),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "ambiguous"

            val authoritativeResult = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("authoritative-spot", 0, "receive", "2", "2"),
                    event(
                        "authoritative-spot-to-staking",
                        1,
                        "transfer",
                        "-1",
                        "1",
                        subtype = "spottostaking",
                        refid = "authoritative-staking-transfer",
                    ),
                    event(
                        "authoritative-staking",
                        1,
                        "transfer",
                        "1",
                        "1",
                        subtype = "spottostaking",
                        refid = "authoritative-staking-transfer",
                    ),
                    event(
                        "authoritative-sweep",
                        2,
                        "spend",
                        "-1",
                        "0",
                        subtype = "dustsweeping",
                    ),
                ),
            )

            authoritativeResult.isValid shouldBe false
            requireNotNull(authoritativeResult.failure).diagnostic shouldContain "replay semantics"
        }

        "rejects duplicate ledger identities" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("same-id", 0, "receive", "1", "1"),
                    event("same-id", 1, "receive", "1", "2"),
                ),
            )

            result.isValid shouldBe false
            val failure = requireNotNull(result.failure)
            failure.diagnostic shouldContain "duplicate ledger identity"
            failure.diagnostic shouldNotContain "same-id"
        }

        "keeps transfer airdrops in the ordinary Spot scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("airdrop", 0, "transfer", "1", "1", subtype = "airdrop"),
                    event("after", 1, "receive", "1", "2"),
                ),
            )

            result.isValid shouldBe true
            result.scopeCount shouldBe 1
        }

        "rejects incomplete internal transfer groups" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "incomplete-transfer",
                        1,
                        "transfer",
                        "1",
                        "1",
                        subtype = "spottostaking",
                        refid = "incomplete-transfer",
                    ),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "complete linked group"

            val unknownScope = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "allocation-debit",
                        1,
                        "transfer",
                        "-1",
                        "1",
                        subtype = "allocation",
                        refid = "allocation-pair",
                    ),
                    event(
                        "allocation-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        subtype = "allocation",
                        refid = "allocation-pair",
                    ),
                ),
            )

            unknownScope.isValid shouldBe false
            requireNotNull(unknownScope.failure).diagnostic shouldContain "no known balance scope"

            val missingRefid = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "missing-refid",
                        1,
                        "transfer",
                        "1",
                        "1",
                        subtype = "spottostaking",
                    ),
                ),
            )
            val whitespaceRefid = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "whitespace-refid",
                        1,
                        "transfer",
                        "1",
                        "1",
                        subtype = "spottostaking",
                        refid = "   ",
                    ),
                ),
            )
            val nonAuthoritativeMissingRefid = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "non-authoritative-missing-refid",
                        1,
                        "transfer",
                        "1",
                        "1",
                        subtype = "spottostaking",
                        authoritativeBalance = false,
                    ),
                ),
            )
            val sameDirection = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "same-direction-one",
                        1,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "spottostaking",
                        refid = "same-direction",
                    ),
                    event(
                        "same-direction-two",
                        1,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "spottostaking",
                        refid = "same-direction",
                    ),
                ),
            )
            val mixedSubtypes = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "internal-leg",
                        1,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "spottostaking",
                        refid = "mixed-subtypes",
                    ),
                    event(
                        "external-leg",
                        1,
                        "transfer",
                        "1",
                        "1",
                        subtype = "airdrop",
                        refid = "mixed-subtypes",
                    ),
                ),
            )
            val zeroDelta = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "negative-leg",
                        1,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "spottostaking",
                        refid = "zero-delta-leg",
                    ),
                    event(
                        "zero-leg",
                        1,
                        "transfer",
                        "0",
                        "0",
                        subtype = "spottostaking",
                        refid = "zero-delta-leg",
                    ),
                ),
            )

            missingRefid.isValid shouldBe false
            whitespaceRefid.isValid shouldBe false
            nonAuthoritativeMissingRefid.isValid shouldBe false
            sameDirection.isValid shouldBe false
            mixedSubtypes.isValid shouldBe false
            zeroDelta.isValid shouldBe false
        }

        "applies a non-authoritative fixed transfer to its existing scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "1", "1"),
                    event(
                        "same-scope-debit",
                        1,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "spottospot",
                        refid = "same-scope-transfer",
                        authoritativeBalance = false,
                    ),
                    event(
                        "same-scope-credit",
                        2,
                        "transfer",
                        "1",
                        "0",
                        subtype = "spottospot",
                        refid = "same-scope-transfer",
                        authoritativeBalance = false,
                    ),
                ),
            )

            result.isValid shouldBe true
            result.nonAuthoritativeEventCount shouldBe 2
        }

        "does not let non-authoritative internal markers create a wallet scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "unobserved-internal-debit",
                        0,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "spottospot",
                        refid = "unobserved-internal",
                        authoritativeBalance = false,
                    ),
                    event(
                        "unobserved-internal-credit",
                        0,
                        "transfer",
                        "1",
                        "0",
                        subtype = "spottospot",
                        refid = "unobserved-internal",
                        authoritativeBalance = false,
                    ),
                    event("first-authoritative-scope", 1, "receive", "1", "1"),
                ),
            )

            result.isValid shouldBe true
            result.scopeCount shouldBe 1
            result.nonAuthoritativeEventCount shouldBe 2
            result.resolvedScopes["unobserved-internal-debit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
            result.resolvedScopes["unobserved-internal-credit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
        }

        "normalizes Kraken asset aliases before continuity validation" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("legacy", 0, "receive", "1", "1", asset = "XBT"),
                    event("modern", 1, "receive", "1", "2", asset = "BTC"),
                    event("after-alias", 2, "receive", "1", "3", asset = "XBT"),
                ),
            )

            result.isValid shouldBe true
        }

        "accepts complete conversion legs independently by asset" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("usd-before", 0, "receive", "2000", "2000", asset = "USD"),
                    event(
                        "usd-leg",
                        1,
                        "conversion",
                        "-1000",
                        "1000",
                        asset = "USD",
                        refid = "conversion",
                        authoritativeFee = true,
                    ),
                    event(
                        "usdg-leg",
                        1,
                        "conversion",
                        "1000",
                        "1000",
                        asset = "USDG",
                        refid = "conversion",
                        authoritativeFee = true,
                    ),
                ),
            )

            result.isValid shouldBe true
            result.groupedEventCheckpointCount shouldBe 2
        }

        "rejects unknown and partial conversion rows before scope replay" {
            val unknown = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("unknown", 0, "mystery", "1", "1")),
            )
            val unknownSynthetic = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("unknown-synthetic", 0, "mystery", "1", "0", authoritativeBalance = false)),
            )
            val partialConversion = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "partial-conversion",
                        0,
                        "conversion",
                        "1",
                        "1",
                        refid = "partial-conversion",
                        authoritativeFee = true,
                    ),
                ),
            )
            val blankConversion = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "blank-conversion",
                        0,
                        "conversion",
                        "1",
                        "1",
                        authoritativeFee = true,
                        authoritativeBalance = false,
                    ),
                ),
            )
            val whitespaceRefidConversion = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "whitespace-refid-conversion",
                        0,
                        "conversion",
                        "1",
                        "1",
                        refid = "   ",
                        authoritativeFee = true,
                    ),
                ),
            )
            val mixedConversion = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "mixed-conversion",
                        0,
                        "conversion",
                        "-1",
                        "1",
                        asset = "BTC",
                        refid = "mixed-conversion",
                        authoritativeFee = true,
                    ),
                    event(
                        "mixed-receive",
                        0,
                        "receive",
                        "1",
                        "1",
                        asset = "ETH",
                        refid = "mixed-conversion",
                    ),
                ),
            )
            val sameNormalizedAssetConversion = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "same-normalized-debit",
                        0,
                        "conversion",
                        "-1",
                        "1",
                        asset = "XBT",
                        refid = "same-normalized-conversion",
                        authoritativeFee = true,
                    ),
                    event(
                        "same-normalized-credit",
                        0,
                        "conversion",
                        "1",
                        "1",
                        asset = "BTC",
                        refid = "same-normalized-conversion",
                        authoritativeFee = true,
                    ),
                ),
            )

            unknown.isValid shouldBe false
            requireNotNull(unknown.failure).diagnostic shouldContain "unsupported ledger type"
            unknownSynthetic.isValid shouldBe false
            requireNotNull(unknownSynthetic.failure).diagnostic shouldContain "observed=n/a"
            partialConversion.isValid shouldBe false
            requireNotNull(partialConversion.failure).diagnostic shouldContain "complete linked two-leg"
            blankConversion.isValid shouldBe false
            requireNotNull(blankConversion.failure).diagnostic shouldContain "observed=n/a"
            mixedConversion.isValid shouldBe false
            requireNotNull(mixedConversion.failure).diagnostic shouldContain "complete linked two-leg"
            sameNormalizedAssetConversion.isValid shouldBe false
            requireNotNull(sameNormalizedAssetConversion.failure).diagnostic shouldContain "complete linked two-leg"
            whitespaceRefidConversion.isValid shouldBe false
            requireNotNull(whitespaceRefidConversion.failure).diagnostic shouldContain "complete linked two-leg"
        }

        "rejects Earn internal markers whose wallet scope is opaque" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("earn-allocation", 0, "earn", "1", "1", subtype = "allocation")),
            )
            val unobserved = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("known-scope", 0, "receive", "1", "1"),
                    event(
                        "unobserved-earn-allocation",
                        1,
                        "earn",
                        "1",
                        "1",
                        subtype = "allocation",
                        authoritativeBalance = false,
                    ),
                    event("known-scope-after", 2, "receive", "1", "2"),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "no known balance scope"
            unobserved.isValid shouldBe true
        }

        "rejects malformed fee metadata before attempting continuity" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("invalid-fee", 0, "receive", "1", "1", fee = "0.1", validFee = false)),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "invalid or non-authoritative fee"
        }

        "accepts an empty sequence and ignores an unscoped non-authoritative row" {
            AuthoritativeLedgerBalanceValidator.validate(emptyList()).isValid shouldBe true

            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("synthetic", 0, "reward", "1", "0", authoritativeBalance = false),
                    event("authoritative", 1, "receive", "2", "2", refid = " "),
                ),
            )

            result.isValid shouldBe true
            result.nonAuthoritativeEventCount shouldBe 1
        }

        "keeps a known Spot scope for a non-authoritative fixed event" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("seed", 0, "receive", "1", "1"),
                    event(
                        "unobserved-transfer",
                        1,
                        "transfer",
                        "0.5",
                        "0",
                        authoritativeBalance = false,
                    ),
                    event("after", 2, "receive", "1", "2.5"),
                ),
            )

            result.isValid shouldBe true
        }

        "reports the previous event context without exposing ledger identities" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("seed", 0, "receive", "1", "1", subtype = "initial"),
                    event("mismatch", 1, "receive", "1", "3"),
                ),
            )

            result.isValid shouldBe false
            val diagnostic = requireNotNull(result.failure).diagnostic
            diagnostic shouldContain "previousType=receive"
            diagnostic shouldContain "previousSubtype=initial"
            diagnostic shouldNotContain "mismatch"
        }

        "renders missing optional diagnostic context safely" {
            val failure = AuthoritativeLedgerBalanceValidator.ValidationFailure(
                asset = "USD",
                scope = null,
                previousTime = start,
                previousType = null,
                previousSubtype = null,
                currentTime = start.plusSeconds(1),
                currentType = "receive",
                currentSubtype = null,
                expected = BigDecimal.ONE,
                observed = BigDecimal.TEN,
                delta = BigDecimal.ONE,
                detail = "test",
            )

            failure.diagnostic shouldContain "previousType=unknown"
            failure.diagnostic shouldContain "previousSubtype="
        }

        "omits an observed balance when the duplicated first row is synthetic" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("duplicate", 0, "receive", "1", "0", authoritativeBalance = false),
                    event("duplicate", 1, "receive", "1", "1"),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "observed=n/a"
        }

        "fails closed when non-authoritative staking cannot identify a scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("seed", 0, "receive", "1", "1"),
                    event("unobserved-staking", 1, "staking", "0.1", "0", authoritativeBalance = false),
                    event("after", 2, "receive", "1", "2.1"),
                ),
            )

            result.isValid shouldBe false
        }

        "skips a non-authoritative sweep that has no closing scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("seed", 0, "receive", "1", "1"),
                    event(
                        "unclosed-sweep",
                        1,
                        "spend",
                        "-0.5",
                        "0",
                        subtype = "dustsweeping",
                        authoritativeBalance = false,
                    ),
                    event("after", 2, "receive", "1", "2"),
                ),
            )

            result.isValid shouldBe true
        }

        "allows an authoritative dust sweep to seed or close Spot" {
            val seeded = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("seeded-sweep", 0, "spend", "-1", "0", subtype = "dustsweeping")),
            )
            val closed = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("seed", 0, "receive", "1", "1"),
                    event("closed-sweep", 1, "spend", "-1", "0", subtype = "dustsweeping"),
                ),
            )

            seeded.isValid shouldBe true
            closed.isValid shouldBe true
        }

        "rejects negative and non-authoritative nonzero fees" {
            val negative = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("negative-fee", 0, "receive", "1", "1", fee = "-0.1")),
            )
            val nonAuthoritative = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "untrusted-fee",
                        0,
                        "receive",
                        "1",
                        "0",
                        fee = "0.1",
                        authoritativeBalance = false,
                        authoritativeFee = false,
                    ),
                ),
            )

            negative.isValid shouldBe false
            nonAuthoritative.isValid shouldBe false
        }

        "fails closed when a same-timestamp group exceeds the search bound" {
            val events = (0 until 13).map { index ->
                event(
                    "oversized-$index",
                    0,
                    "receive",
                    "0",
                    "0",
                    authoritativeBalance = index != 0,
                )
            }

            val result = AuthoritativeLedgerBalanceValidator.validate(events)

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "same-timestamp group"

            val authoritativeFirst = (0 until 13).map { index ->
                event(
                    "oversized-authoritative-$index",
                    0,
                    "receive",
                    "0",
                    "0",
                    authoritativeBalance = index == 0,
                )
            }
            AuthoritativeLedgerBalanceValidator.validate(authoritativeFirst).isValid shouldBe false
        }

        "recognizes every documented internal transfer direction without merging scopes" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "spottostaking-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET01",
                        subtype = "spottostaking",
                        refid = "spottostaking-pair",
                    ),
                    event(
                        "spottostaking-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = "ASSET01",
                        subtype = "spottostaking",
                        refid = "spottostaking-pair",
                    ),
                    event(
                        "stakingfromspot-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET02",
                        subtype = "stakingfromspot",
                        refid = "stakingfromspot-pair",
                    ),
                    event(
                        "stakingfromspot-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = "ASSET02",
                        subtype = "stakingfromspot",
                        refid = "stakingfromspot-pair",
                    ),
                    event(
                        "stakingtospot-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET03",
                        subtype = "stakingtospot",
                        refid = "stakingtospot-pair",
                    ),
                    event(
                        "stakingtospot-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = "ASSET03",
                        subtype = "stakingtospot",
                        refid = "stakingtospot-pair",
                    ),
                    event(
                        "spotfromstaking-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET04",
                        subtype = "spotfromstaking",
                        refid = "spotfromstaking-pair",
                    ),
                    event(
                        "spotfromstaking-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = "ASSET04",
                        subtype = "spotfromstaking",
                        refid = "spotfromstaking-pair",
                    ),
                    event(
                        "spottofutures-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET05",
                        subtype = "spottofutures",
                        refid = "spottofutures-pair",
                    ),
                    event(
                        "spottofutures-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = "ASSET05",
                        subtype = "spottofutures",
                        refid = "spottofutures-pair",
                    ),
                    event(
                        "spotfromfutures-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET06",
                        subtype = "spotfromfutures",
                        refid = "spotfromfutures-pair",
                    ),
                    event(
                        "spotfromfutures-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = "ASSET06",
                        subtype = "spotfromfutures",
                        refid = "spotfromfutures-pair",
                    ),
                    event(
                        "spottospot-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET07",
                        subtype = "spottospot",
                        refid = "spottospot-pair",
                    ),
                    event(
                        "spottospot-credit",
                        1,
                        "transfer",
                        "1",
                        "2",
                        asset = "ASSET07",
                        subtype = "spottospot",
                        refid = "spottospot-pair",
                    ),
                    event(
                        "spotfromspot-debit",
                        0,
                        "transfer",
                        "-1",
                        "1",
                        asset = "ASSET08",
                        subtype = "spotfromspot",
                        refid = "spotfromspot-pair",
                    ),
                    event(
                        "spotfromspot-credit",
                        1,
                        "transfer",
                        "1",
                        "2",
                        asset = "ASSET08",
                        subtype = "spotfromspot",
                        refid = "spotfromspot-pair",
                    ),
                ),
            )

            result.isValid shouldBe true
            result.scopeCount shouldBe 14
        }

        "can continue staking from the Spot scope and reject an unassignable reward" {
            val continued = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "1", "1"),
                    event("spot-staking", 1, "staking", "0.1", "1.1"),
                ),
            )
            val unassignable = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("unassignable", 0, "staking", "1", "2")),
            )

            continued.isValid shouldBe true
            unassignable.isValid shouldBe false
        }

        "retains a Spot staking candidate when a canonical staking scope also matches" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "2", "2"),
                    event(
                        "spot-to-staking",
                        1,
                        "transfer",
                        "-1",
                        "1",
                        subtype = "spottostaking",
                        refid = "initial-staking-transfer",
                    ),
                    event(
                        "staking-scope",
                        1,
                        "transfer",
                        "1",
                        "1",
                        subtype = "spottostaking",
                        refid = "initial-staking-transfer",
                    ),
                    event("staking-credit", 2, "staking", "0.1", "1.1"),
                    event(
                        "spot-debit",
                        3,
                        "transfer",
                        "-0.1",
                        "1",
                        subtype = "spottostaking",
                        refid = "return-staking-transfer",
                    ),
                    event(
                        "staking-return",
                        3,
                        "transfer",
                        "0.1",
                        "1.1",
                        subtype = "spottostaking",
                        refid = "return-staking-transfer",
                    ),
                ),
            )

            result.isValid shouldBe true
            result.scopeCount shouldBe 2
        }

        "fails closed when equal balances have different replay scope assignments" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "2", "2", asset = "SOL"),
                    event(
                        "spot-to-staking",
                        1,
                        "transfer",
                        "-1",
                        "1",
                        asset = "SOL",
                        subtype = "spottostaking",
                        refid = "initial-staking-transfer",
                    ),
                    event(
                        "staking-scope",
                        1,
                        "transfer",
                        "1",
                        "1",
                        asset = "SOL",
                        subtype = "spottostaking",
                        refid = "initial-staking-transfer",
                    ),
                    event("ambiguous-staking-row", 2, "staking", "0.1", "1.1", asset = "SOL"),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "replay semantics"
        }

        "detects replay-scope ambiguity while equivalent balance states are being searched" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "2", "2", asset = "SOL"),
                    event(
                        "spot-to-staking",
                        1,
                        "transfer",
                        "-1",
                        "1",
                        asset = "SOL",
                        subtype = "spottostaking",
                        refid = "initial-staking-transfer",
                    ),
                    event(
                        "staking-scope",
                        1,
                        "transfer",
                        "1",
                        "1",
                        asset = "SOL",
                        subtype = "spottostaking",
                        refid = "initial-staking-transfer",
                    ),
                    event("staking-a", 2, "staking", "0.1", "1.1", asset = "SOL"),
                    event("staking-b", 2, "staking", "0.1", "1.1", asset = "SOL"),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain "replay semantics"
        }

        "maps same-asset transfers across independent wallet scopes" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("spot", 0, "receive", "10", "10", asset = "SOL"),
                    event(
                        "staking-debit",
                        1,
                        "transfer",
                        "-4",
                        "6",
                        asset = "SOL",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event(
                        "staking-credit",
                        1,
                        "transfer",
                        "4",
                        "4",
                        asset = "SOL",
                        subtype = "spottostaking",
                        refid = "staking-transfer",
                    ),
                    event(
                        "futures-debit",
                        2,
                        "transfer",
                        "-1",
                        "5",
                        asset = "SOL",
                        subtype = "spottofutures",
                        refid = "futures-transfer",
                    ),
                    event(
                        "futures-credit",
                        2,
                        "transfer",
                        "1",
                        "1",
                        asset = "SOL",
                        subtype = "spottofutures",
                        refid = "futures-transfer",
                    ),
                ),
            )

            result.isValid shouldBe true
            result.scopeCount shouldBe 3
            result.resolvedScopes["staking-debit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
            result.resolvedScopes["staking-credit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING
            result.resolvedScopes["futures-debit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
            result.resolvedScopes["futures-credit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.FUTURES
        }

        "rejects a complete internal subtype without a known wallet scope" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "allocation-debit",
                        0,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "allocation",
                        refid = "unscoped-transfer",
                    ),
                    event(
                        "allocation-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        subtype = "allocation",
                        refid = "unscoped-transfer",
                    ),
                ),
            )

            result.isValid shouldBe false
            requireNotNull(result.failure).diagnostic shouldContain
                "internal transfer subtype has no known balance scope"

            val unobserved = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "unobserved-allocation-debit",
                        0,
                        "transfer",
                        "-1",
                        "0",
                        subtype = "allocation",
                        refid = "unobserved-allocation-transfer",
                        authoritativeBalance = false,
                    ),
                    event(
                        "unobserved-allocation-credit",
                        0,
                        "transfer",
                        "1",
                        "0",
                        subtype = "allocation",
                        refid = "unobserved-allocation-transfer",
                        authoritativeBalance = false,
                    ),
                ),
            )

            unobserved.isValid shouldBe false
            requireNotNull(unobserved.failure).diagnostic shouldContain "observed=n/a"
        }

        "accepts the observed SOL03/SOL internal alias but rejects arbitrary cross-asset pairs" {
            val observedAlias = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "sol03-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = "SOL03",
                        subtype = "spottostaking",
                        refid = "observed-sol-transfer",
                    ),
                    event(
                        "sol-debit",
                        0,
                        "transfer",
                        "-1",
                        "0",
                        asset = Asset.SOL,
                        subtype = "spottostaking",
                        refid = "observed-sol-transfer",
                    ),
                ),
            )
            val arbitraryPair = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event(
                        "btc-debit",
                        0,
                        "transfer",
                        "-1",
                        "0",
                        asset = Asset.BTC,
                        subtype = "spottostaking",
                        refid = "arbitrary-cross-asset",
                    ),
                    event(
                        "eth-credit",
                        0,
                        "transfer",
                        "1",
                        "1",
                        asset = Asset.ETH,
                        subtype = "spottostaking",
                        refid = "arbitrary-cross-asset",
                    ),
                ),
            )

            observedAlias.isValid shouldBe true
            observedAlias.resolvedScopes["sol03-credit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING
            observedAlias.resolvedScopes["sol-debit"] shouldBe
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT
            arbitraryPair.isValid shouldBe false
            requireNotNull(arbitraryPair.failure).diagnostic shouldContain "complete linked group"
        }

        "allocates successive opaque staking scopes and fails closed on too many alternatives" {
            val opaqueScopes = (0 until 6).map { index ->
                event("opaque-$index", index.toLong(), "staking", "1", "1")
            }
            val seeded = AuthoritativeLedgerBalanceValidator.validate(opaqueScopes)
            seeded.isValid shouldBe true
            seeded.scopeCount shouldBe 6
            val ambiguousGroup = (1..6).map { amount ->
                event(
                    "ambiguous-$amount",
                    10,
                    "staking",
                    amount.toString(),
                    (amount + 1).toString(),
                )
            }

            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(event("unobserved", -1, "reward", "0", "0", authoritativeBalance = false)) +
                    opaqueScopes + ambiguousGroup,
            )

            result.isValid shouldBe false
            val diagnostic = requireNotNull(result.failure).diagnostic
            diagnostic shouldContain "bounded alternatives"
            diagnostic shouldContain "observed=n/a"

            val impossibleGroup = (1..9).map { amount ->
                event(
                    "impossible-$amount",
                    20,
                    "staking",
                    amount.toString(),
                    (amount + 1).toString(),
                )
            }
            val budgeted = AuthoritativeLedgerBalanceValidator.validate(opaqueScopes + impossibleGroup)
            budgeted.isValid shouldBe false
            requireNotNull(budgeted.failure).diagnostic shouldContain "bounded alternatives"
        }

        "accepts an eight-decimal fee that is exactly representable at legacy precision" {
            val result = AuthoritativeLedgerBalanceValidator.validate(
                listOf(
                    event("seed", 0, "receive", "1", "1"),
                    event(
                        "padded-fee",
                        1,
                        "receive",
                        "0.1",
                        "1.09",
                        fee = "0.01000000",
                    ),
                ),
            )

            result.isValid shouldBe true
        }

        "validates ATOM ledger sequence with staking reward and dust sweeping" {
            val events = listOf(
                event("trade-1", 100, "trade", "43.41408352", "43.41408352", asset = "ATOM"),
                event("trade-2", 200, "trade", "-43.41408352", "0", asset = "ATOM"),
                event("trade-3", 300, "trade", "43.95797617", "43.95797617", asset = "ATOM"),
                event("trade-4", 400, "trade", "-43.95797617", "0", asset = "ATOM"),
                event("trade-5", 500, "trade", "43.73305343", "43.73305343", asset = "ATOM"),
                event("trade-6", 600, "trade", "-43.73305343", "0", asset = "ATOM"),
                event("trade-7", 700, "trade", "42.73760000", "42.73760000", asset = "ATOM"),
                event("trade-8", 701, "trade", "0.00008153", "42.73768153", asset = "ATOM"),
                event("trade-9", 800, "trade", "-42.73768153", "0", asset = "ATOM"),
                event("trade-10", 900, "trade", "306.53043092", "306.53043092", asset = "ATOM"),
                event(
                    "transfer-spot",
                    1000,
                    "transfer",
                    "-306.53043092",
                    "0",
                    asset = "ATOM",
                    subtype = "spottostaking",
                    refid = "t1",
                ),
                event(
                    "transfer-stake",
                    1000,
                    "transfer",
                    "306.53043092",
                    "306.53043092",
                    asset = "ATOM",
                    subtype = "spottostaking",
                    refid = "t1",
                ),
                event("staking-1", 1100, "staking", "0.01491875", "306.54087405", asset = "ATOM", fee = "0.00447562"),
                event("staking-2", 1101, "staking", "0.02860726", "0.02002509", asset = "ATOM", fee = "0.00858217"),
                event("dust-1", 1200, "spend", "-0.02002509", "0", asset = "ATOM", subtype = "dustsweeping"),
                event(
                    "transfer-back-stake",
                    1300,
                    "transfer",
                    "-306.54087405",
                    "0",
                    asset = "ATOM",
                    subtype = "stakingtospot",
                    refid = "t2",
                ),
                event(
                    "transfer-back-spot",
                    1300,
                    "transfer",
                    "306.54087405",
                    "306.54087405",
                    asset = "ATOM",
                    subtype = "stakingtospot",
                    refid = "t2",
                ),
                event("trade-sell", 1400, "trade", "-306.54087405", "0", asset = "ATOM"),
                event("staking-3", 1500, "staking", "0.03869550", "0.02708685", asset = "ATOM", fee = "0.01160865"),
                event("staking-4", 1501, "staking", "0.11871749", "0.11018910", asset = "ATOM", fee = "0.03561524"),
                event("dust-2", 1600, "spend", "-0.11018910", "0", asset = "ATOM", subtype = "dustsweeping"),
                event("staking-5", 1700, "staking", "0.00002662", "0.00001864", asset = "ATOM", fee = "0.00000798"),
                event("dust-3", 1800, "spend", "-0.00001864", "0", asset = "ATOM", subtype = "dustsweeping"),
            )

            val result = AuthoritativeLedgerBalanceValidator.validate(events)
            result.isValid shouldBe true
        }
    }

    private fun event(
        id: String,
        seconds: Long,
        type: String,
        amount: String,
        balance: String,
        asset: String = "USD",
        fee: String = "0",
        refid: String? = null,
        subtype: String? = null,
        authoritativeBalance: Boolean = true,
        authoritativeFee: Boolean = fee != "0",
        validFee: Boolean = true,
        validAmount: Boolean = true,
    ) = com.gemini.krakenbot.model.LedgerEvent(
        ledgerId = id,
        refid = refid,
        time = start.plusSeconds(seconds),
        type = type,
        subtype = subtype,
        asset = asset,
        amount = BigDecimal(amount),
        fee = BigDecimal(fee),
        balance = BigDecimal(balance),
        hasAuthoritativeBalance = authoritativeBalance,
        hasAuthoritativeFee = authoritativeFee,
        hasValidFee = validFee,
        hasValidAmount = validAmount,
    )
}
