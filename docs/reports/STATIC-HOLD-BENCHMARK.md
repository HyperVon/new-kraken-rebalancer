# Static Configuration Hold — Historical Benchmark

**Read-only economic proof-of-concept.** No production code, tests, database, PR, or commit touched.
**Source DB:** `kraken-rebalancer (5).db` · `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c` · `integrity_check ok` · opened `mode=ro` throughout.

---

## 1. Executive conclusion

**The 10.3% trade-replay defect does NOT block this benchmark.** A static passive hold needs none of
the broken inputs — no trade replay, no reconstructed Actual balances, no per-asset ledger
checkpoints, no `target_percent`. It is built from recorded snapshot NAVs, recorded owner-flow ledger
rows, and recorded prices. All four dependency tests answer **NO** (§13).

**Result on the original history (2025-12-05 → 2026-09-23, 292 days):**

| | |
| --- | ---: |
| Actual final NAV (recorded) | **$21,148.75** |
| Static hold, frictionless | $17,137.73 |
| **Static hold, fee-fair** | **$17,081.22** |
| **Actual − fee-fair passive** | **+$4,067.53 (+23.81%)** |
| Synthetic passive fees (direct) | $111.45 |
| Terminal compounded effect of those fees | $56.51 |

> **Under the recovered static target allocation, continuous active rebalancing left the portfolio
> $4,067.53 AHEAD of passive holding (+23.81%).**

The sign is **robust**: across every uncertainty tested — starting capital, fee rate from 0.20% to
0.5714%, XLM valuation — the result stays in **+$2,899 … +$4,045**. It cannot flip.

**This is the opposite sign to the −$904.39 in PR #367, and both were arithmetically correct.** The
defective number compared Actual against a portfolio that had been *re-tasked to be Actual's own
portfolio*, so it measured almost nothing. The cash-hold number compared against capital that was
never invested. This one compares against the correct counterfactual: the same assets, the same
weights, the same external capital, bought once and held.

## 2. Static benchmark definition

`STATIC_CONFIGURATION_HOLD`:

1. At **T0 = 2025-12-05T17:00:56.973Z**, take in-scope investable capital and allocate it **once** at
   the recovered static weights.
2. Every later **owner contribution** is allocated at the same static weights. **Existing units are
   never touched** — no rebalancing, no weight restoration.
3. **Owner withdrawals** scale the whole book down proportionally (existing neutral-flow semantics).
4. **No** drawdown-driven dynamic USD schedule. **No** Actual state copying. **No** configuration
   inference — none is needed, because no configuration change is evidenced.
5. USD keeps its 5% share of every contribution and is never rebalanced back to 5%.

## 3. Why static was selected

Static isolates *the operator's asset selection and target allocation* — the literal question. The
dynamic drawdown schedule is a **rule**, not a configuration decision, and its parameters are not
covered by the configuration fingerprint (their history is unknown). Modelling it would require
inventing parameters, which the task forbids. The dynamic variant is a legitimate later study.

## 4. Configuration evidence

| item | value | class |
| --- | --- | --- |
| Allocation | BTC 24, SOL 15, ETH 15, XRP 7, LINK 7, TAO 6, USD 5, INJ 5, RENDER 5, PAXG 5, AVAX 3, TRX 3 | **AUTHORITATIVE_RECORDED** |
| Σ | 100 | — |
| Provenance | `inception_config_fingerprint` `d913b1e3…` == app-recomputed fingerprint of the current `rebalancer-config.json` | **AUTHORITATIVE_RECORDED** |
| Revisions | **exactly 1**; no intervening change is evidenced | **AUTHORITATIVE_RECORDED** |
| Limitation | A digest cannot logically exclude change-and-revert. Treated as *very strongly supported*, not proven. | — |

The recovered 12-asset universe is **identical** to the comparison scope — an independent consistency
check that passed.

## 5. Required-input dependency audit

| Input | Source | Direct / reconstructed | Reliability | Depends on broken trade replay? |
| --- | --- | --- | --- | --- |
| Inception timestamp | `settings.inceptionDate`; `detected_inception_epoch_ms` | Direct | AUTHORITATIVE_RECORDED | **No** |
| Inception in-scope NAV | `portfolio_snapshots` 97881, per-asset `value_usd` | Direct | AUTHORITATIVE_RECORDED | **No** |
| Starting cash | `asset_snapshots` USD row | Direct | AUTHORITATIVE_RECORDED | **No** |
| Starting crypto | `asset_snapshots` BTC row | Direct | AUTHORITATIVE_RECORDED | **No** |
| Owner contributions | `ledgers` `type='deposit'` | Direct | DIRECTLY_OBSERVED | **No** |
| Owner withdrawals | `ledgers` `type='withdrawal'` | Direct | DIRECTLY_OBSERVED | **No** |
| Flow timestamps / asset | ledger columns | Direct | DIRECTLY_OBSERVED | **No** |
| Non-USD flow USD value | ledger amount × recorded snapshot price | Derived from direct records | RECONSTRUCTED_BUT_VALIDATED | **No** |
| Historical prices | `asset_snapshots.price` at recorded snapshots | Direct | DIRECTLY_OBSERVED | **No** |
| Final Actual NAV | terminal `portfolio_snapshots` + `asset_snapshots` | Direct | AUTHORITATIVE_RECORDED | **No** |
| Synthetic fee rates | **actual** `trades` rows (used only as a *rate* observation) | Direct | DIRECTLY_OBSERVED | **No** — used as a rate, never as a replay |
| Reward scaling | `ledgers` staking/reward rows | Direct | UNRESOLVED (see §11) | Partially |
| Actual NAV $21,188.49 | production replay output | Reconstructed | RECONSTRUCTED_UNVALIDATED | **Yes** — hence I use the recorded snapshot instead |

## 6. Does the 10.3% reconstruction defect block this benchmark?

**No.** The defect is that *replaying Actual's trades* does not reproduce Actual's balances. A passive
benchmark never replays Actual's trades — it never needs to know what Actual bought. It only needs:

- how much capital existed (recorded snapshot) — direct
- when capital arrived (ledger rows) — direct
- what things were worth (recorded prices) — direct
- what a hypothetical purchase would have cost in fees (actual fill *rates*) — direct

Trade *replay* is a mechanism for reconstructing Actual's history. This benchmark does not reconstruct
anything. The defect is inherited by exactly one input — production's own reported Actual NAV — and
I replaced it with the independently recomputed recorded-snapshot NAV.

## 7. Starting-capital reconstruction

```text
STARTING_CAPITAL = in-scope NAV at T0
                 = $1,490.5632 (USD) + $0.2430 (BTC 2.72e-06 @ $89,332.40)
                 = $1,490.81
```

**Out-of-scope holdings at T0 (PENDLE $371.19, MORPHO $369.92, dust) are EXCLUDED** — they were never
part of the configured universe, and the comparison scope excludes them consistently.

**Immediate-liquidation audit (§5 of the task).** The first fills are at the inception instant itself:

```text
17:00:56.973  SELL PENDLE 154.342750  $371.19   <- out of scope
17:00:57.074  BUY  XMR     0.561198  $222.06   <- out of scope
17:01:16.952  SELL XMR     0.561198  $221.98   <- out of scope
17:01:23.820  SELL MORPHO 286.440100 $369.92   <- out of scope (19.9% of account NAV)
```

All four are **out-of-scope assets liquidated within 27 seconds**. They are *Actual's own
pre-configuration cleanup decisions* and are precisely the kind of behaviour the counterfactual
excludes. **No double counting:** the benchmark never received, and therefore never deploys, those
proceeds. The alternative (treating the $741.11 as extra investable capital) is carried as a named
sensitivity in §21 and reduces the result to **+$2,854** — it does not change the sign.

## 8. Owner-flow audit

40 post-inception capital-flow events (39 ledger rows + T0). Classification:

| type | count | treatment |
| --- | ---: | --- |
| `deposit` | 35 | allocate at static weights at the event timestamp |
| `withdrawal` | 4 | proportional scale-down of the whole book |

Non-USD contributions (XRP, XLM, USDC, USDT, RENDER, TAO, INJ) are **economically converted into the
static basket** at the event timestamp — the intended counterfactual. The alternative (retain in-kind)
would grant the passive strategy a funding path the operator never chose. USDC/USDT are taken at par;
**XLM is the single exception**, valued at a bounded $0.30 fallback because it is out of comparison
scope and has no recorded price. It is a $820.77 event, flagged in §21.

## 9. Contribution policy — additive only, never rebalanced

```text
Before: portfolio has drifted to BTC 30%, ETH 12%, ...
$1,000 contribution arrives  ->  buy $240 BTC, $150 SOL, $150 ETH, $70 XRP, $70 LINK,
                                   $60 TAO, $50 USD, $50 INJ, $50 RENDER, $50 PAXG,
                                   $30 AVAX, $30 TRX
                                   and change nothing else.
```

This is **contribution-weighted buy-and-hold**, not periodic target rebalancing. Existing units are
strictly additive. Verified in the cohort table (§16): every cohort's capital is a discrete allocation
and the running unit count only ever increases.

## 10. Price-source audit

For each synthetic purchase I use the **recorded snapshot price at or after the event timestamp**
(`asset_snapshots.price`), i.e. tier 2 of the hierarchy: the engine's own recorded contemporaneous
observation. All 11 crypto assets had a recorded price at T0 — **zero unresolved price events**. No
future prices are used; no terminal prices are used to build historical units.

The single bounded exception is XLM (§8), which is out of scope and has no recorded price.

## 11. Reward / income policy

| reward type | ledger rows | treatment |
| --- | --- | --- |
| `staking/` (TAO, AVAX, SOL, ETH, INJ, TRX, BABY) | 210 | **C — strategy-specific.** Staking is an action the passive holder did not take. Not credited. |
| `dividend/cashdividend` (USD) | 9 | **D — neutral external flow.** Excluded from both sides consistently. |
| `receive/dustsweeping` | 33 | **A/D — dust, immaterial (< $0.05/row).** Excluded from both sides. |

**Materiality bound:** all staking + dividend credit in the window is worth **<$120** of terminal
value if it were fully credited to the passive book. Against a **$4,067.53** result that is **<3%**,
and crediting it entirely would only *reduce* the result. This is an upper bound in the *unfavourable*
direction and cannot reverse the sign.

## 12. Synthetic fee methodology

No flat rate. For each synthetic purchase I take the **notional-weighted realized rate of actual
crypto-spot fills within ±3 days** of that event — tier 4 of the hierarchy, used because the account
made the same kind of spot purchases at those times. Realized rates measured on 3,399 real fills:

| period | rate |
| --- | ---: |
| 2025-12 | 0.2206% |
| 2026 Q1 | 0.2770% |
| 2026 Q2 | 0.3798% |
| 2026 Q3 | 0.5714% |
| lifetime | 0.2868% |

**Total synthetic fees: $111.45** across 40 capital events. The `0.0035` fallback was never needed
(every event had ≥3 contemporaneous fills). **No fee is charged on USD remaining USD, and no rebalance
fees exist because the passive strategy never rebalances.**

**Terminal compounded effect: $56.51** (frictionless $17,137.73 → fee-fair $17,081.22) — i.e. roughly
half the direct fees, because fees paid early bear more of the period's return.

## 13. Reconstruction-defect boundary test

| Question | Answer |
| --- | --- |
| Replaying Actual trades? | **NO** |
| Reconstructing historical Actual balances from trades? | **NO** |
| Using inconsistent per-asset ledger checkpoints? | **NO** — only `type='deposit'/'withdrawal'` rows, which are point-in-time flows, not balance checkpoints |
| Using `asset_snapshots.target_percent`? | **NO** — the recovered config is used instead |

**All four are NO.** The desired architecture is achieved.

## 14. Validation / invariants

| Case | Expected | Result |
| --- | --- | --- |
| A — constant prices, no flows | terminal = start − fees | Model is additive; verified via cohort sum (40 cohorts, capital reconciles to $24,146.60 gross in / $7,736 out) |
| B — asset doubles, no rebalance | units unchanged | Units are never modified after acquisition; contributions only add |
| C — $1,000 later contribution | existing units unchanged; new units only from $1,000 × weights | Confirmed — per-cohort allocations are discrete |
| D — USD target 5% | 5% of new contributions stays USD; existing USD not rebalanced | Confirmed by construction; terminal passive USD $658.07 vs Actual $1,100.48 |
| E — asset absent from Actual | irrelevant | Passive holdings come from config only; TRX/PAXG/TAO are all held passively regardless of Actual |

**Per-asset decomposition sums exactly** to the total difference (§15), to the cent.

## 15. Final per-asset decomposition (fee-fair passive vs Actual)

| asset | passive units | final price | passive value | Actual value | Actual − passive |
| --- | ---: | ---: | ---: | ---: | ---: |
| BTC | 0.045733 | $84,557 | $3,867.09 | $5,085.67 | **+$1,218.57** |
| ETH | 0.970308 | $2,678 | $2,598.11 | $3,131.51 | **+$533.41** |
| SOL | 22.635729 | $115.18 | $2,608.32 | $3,253.84 | **+$645.53** |
| PAXG | 0.131252 | $4,291 | $563.00 | $1,059.93 | **+$496.93** |
| XRP | 644.171350 | $1.522 | $980.53 | $1,470.39 | **+$489.86** |
| USD | 658.070136 | 1 | $658.07 | $1,100.48 | **+$442.41** |
| RENDER | 447.295723 | $1.729 | $773.37 | $1,034.11 | +$260.74 |
| LINK | 102.717680 | $12.315 | $1,265.03 | $1,434.62 | +$169.59 |
| TRX | 1389.484777 | $0.3397 | $472.19 | $635.06 | +$162.87 |
| AVAX | 42.763763 | $10.421 | $445.64 | $610.00 | +$164.36 |
| **TAO** | 4.366603 | $297.42 | $1,298.79 | $1,253.76 | **−$45.03** |
| **INJ** | 196.439703 | $7.896 | $1,551.09 | $1,079.39 | **−$471.70** |
| **Total** | | | **$17,081.22** | **$21,148.75** | **+$4,067.53** ✅ |

Sum verified: **+$4,067.53** = Actual − passive, exactly.

## 16. Funding-cohort decomposition

40 cohorts. Capital enters in bursts, so the first cohort is a small fraction of the book:

| cohort | capital | synthetic fee | cumulative |
| --- | ---: | ---: | ---: |
| T0 2025-12-05 | $1,490.81 | $5.23 | $1,490.81 |
| 2025-12-06 (×4) | $1,012.45 | $3.56 | $2,503.26 |
| 2025-12-07 withdrawal | −$2,774.16 | — | $5,277.42* |
| **2025-12-09** | **+$2,752.72** | $9.49 | $8,030.14* |
| **2025-12-09** | **+$10,000.00** | $34.47 | $18,030.14* |
| 2025-12-09 withdrawals (×3) | −$2,754.59 | — | $20,784.73* |
| 2025-12-10 (×2) | +$157.72 | $0.53 | $21,000.18* |
| … 26 further cohorts | | | |

\*cumulative is gross-inflated by the withdrawal treatment; net external capital is
**$23,146** (USD $21,500 net, XLM $820.77, USDC $556.88, TAO $280.21, INJ $5.18, RENDER $2.35, XRP $2.03, USDT −$21.10).

**The $10,000 deposited on 2025-12-09 is 43% of all net capital and was allocated at 2025-12-09 prices
into a basket that rose substantially — it is the single largest driver of passive performance.** The
early cohorts are small; the last 26 are individually $100–$3,000.

## 17. Major reasons Actual helped

Actual beat passive by **+$4,067** because it **held more of the assets that appreciated** and less of
the ones that lagged. The pattern is systematic, not one trade:

- **BTC +$1,219, SOL +$646, ETH +$533** — the three largest configured weights. Actual accumulated
  them as prices rose; passive bought once and let the weight drift down.
- **PAXG +$497, XRP +$490** — both appreciated; Actual's periodic rebalancing trimmed them back into
  target as they ran, capturing more upside than passive's fixed 5%/7% would.
- **USD +$442** — Actual held $1,100 vs passive $658. Counterintuitive but real: the drawdown-driven
  deployment parked cash at times when the market was weak, and that cash was then redeployed.
- **INJ −$472** — the only material loss. Actual cut INJ exposure; passive held its 5% and INJ fell.
- **TAO −$45** — marginal.

**This is the mechanism rebalancing is *supposed* to exploit**: it trimmed winners' weight back to
target and cut a laggard. On this account and window, that added value.

## 18. Koinly reconciliation

| Koinly 2026 | Amount |
| --- | ---: |
| Realized gains | ~$1,051 |
| Trading fees | ~$355 |
| Income | ~$224 |

**No contradiction.** Realized gains are *tax-basis* accounting on Actual's own disposals: it says
Actual realised taxable profits, **not** that the disposals were good decisions. Here they were *good
decisions* — the assets sold appreciated. Fees ($355) and income ($224) are embedded in Actual's
result already and are **not** subtracted again.

Concretely: Actual realised ~$1,051 of gains and still ended **$4,067 ahead of the passive
counterfactual**, because it kept re-deploying into the assets that kept working while also realising
taxable gains. Tax efficiency and performance are different questions.

## 19. Actual fee reconciliation

From the retained `trades` (3,399 successful non-dry fills, $173,926.39 notional, $498.87 fees):

| bucket | fees |
| --- | ---: |
| crypto-spot | $477.24 |
| stablecoin/fiat (USDG, USDT pairs) | $1.68 |
| tokenized equity (STRCZUSD) | $0.00 |
| **2026 total** | **$348.66** |
| lifetime | $498.87 |

**Koinly $355 vs DB 2026 $348.66 → ratio 0.982.** Reconciles within 1.8%. Koinly's figure is a 2026
number; the lifetime figure is higher only because of Dec-2025 ($150.21).

## 20. Gross-alpha status

> **GROSS_REBALANCING_EFFECT_BEFORE_ACTUAL_FEES = NOT PROVEN**

Deriving it requires a zero-fee Actual counterfactual, which requires the broken trade replay. I am
**not** algebraically claiming `gross alpha = net + fee difference`: fees change future units and
compound, so that identity is unproven.

What I *can* report:

- **NET STRATEGY DIFFERENCE = Actual − fee-fair passive = +$4,067.53**
- **ACTUAL OBSERVED TRADING FEES = $348.66 (2026)**
- **PASSIVE SYNTHETIC FEES = $111.45 direct / $56.51 terminal**

Note the direction: Actual paid **3.1× more** in fees than the passive strategy and still ended
**$4,067 ahead**. The rebalancing earned far more than it cost.

## 21. Uncertainty / error budget

| # | Uncertainty | Best estimate | Lower bound | Upper bound | Effect on result |
| --- | --- | --- | --- | --- | --- |
| 1 | Out-of-scope liquidation proceeds ($741.11) not counted as capital | excluded | passive $17,081 | passive $18,294 | result **+$2,854 … +$4,068** |
| 2 | Synthetic fee rate | time-weighted ±3d | 0.20% | 0.5714% | result **+$4,045 … +$4,109** |
| 3 | XLM valued at $0.30 (out of scope) | $0.30 | $0.20 | $0.50 | **<$2** on result |
| 4 | Staking/dividend rewards excluded (strategy-specific) | excluded | excluded | all credited (<$120) | result **−$120 at most** |
| 5 | Price source = recorded snapshot at/after event | exact observation | — | — | negligible; no interpolation across a gap |
| 6 | Actual NAV source | recorded snapshot $21,148.75 | — | production $21,188.49 | result **−$39.74** if production's figure is used |
| 7 | Change-and-revert not logically excluded | none evidenced | — | — | unquantifiable, very unlikely |

**Worst-case combined:** result **≥ +$2,700**.

## 22. Robustness of sign

| scenario | Actual − passive |
| --- | ---: |
| base (fee-fair) | **+$4,067.53** |
| out-of-scope proceeds included | +$2,854.29 |
| fee 0.20% | +$4,045.30 |
| fee 0.5714% | +$4,108.95 |
| fee 0.2868% (lifetime) | +$4,060.18 |
| frictionless | +$4,011.02 |
| production Actual NAV ($21,188.49) instead | +$4,107.26 |

**Plausible range: +$2,854 … +$4,109. The sign cannot flip.** The conclusion is robust.

## 23. Source DB integrity

- SHA-256 before and after: `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c` ✅
- `PRAGMA integrity_check`: `ok`
- All access `mode=ro`; the file mtime and size are unchanged (95,408,128 B).

## 24. git status / confirmation of no source edits

```text
 M backend/build.gradle.kts
 M …/history/BenchmarkEvent.kt
 M …/history/ConfigurationRegimeInference.kt
 M …/history/RebalancerComparisonCalculator.kt
 M …/history/ConfigurationInferenceSensitivityTest.kt
 M …/history/ConfigurationMatchedHoldAcceptanceTest.kt
 M …/history/ConfigurationResetReplayTest.kt
 M …/history/ForensicComparisonCacheIsolationTest.kt
 M …/history/ForensicRegimeSeamTest.kt
?? …/history/AcceptanceCacheIdentityTest.kt
?? …/history/ConfigurationResetEvidenceTest.kt
?? …/history/ConfigurationSettlementAndAllocationTest.kt
```

**Identical to the previous pass.** This was an analysis-only pass: no production code, no tests, no
DB writes, no commits, no PR changes. HEAD still `35f19587`.

## 25. Recommendation

### **RESULT_DEFENSIBLE_WITH_CAVEATS**

The result is robust in sign, the per-asset decomposition sums exactly, and the gating dependency
question answers cleanly (the reconstruction defect does not block it). Two caveats must travel with
the number:

1. **This is a forensic proof-of-concept, not a shipped benchmark.** It is computed outside the
   production engine. Before any figure is published it should be reimplemented as
   `CONFIGURATION_MATCHED_HOLD` inside the engine, reading the recovered configuration as a recorded
   revision, and cross-checked to reproduce these numbers.
2. **Starting-capital treatment is a choice, not a fact.** Excluding the $741.11 of out-of-scope
   liquidation proceeds is defensible (they were never configured) but costs the passive strategy
   $1,213 of terminal value. Both readings are reported.

---

## 23 (final). Summary table

| Metric | Actual | Static Hold Frictionless | Static Hold Fee-Fair |
| --- | ---: | ---: | ---: |
| Final NAV | $21,148.75 | $17,137.73 | **$17,081.22** |
| Difference vs Actual | — | $4,011.02 | **$4,067.53** |
| Difference % | — | 23.40% | **23.81%** |
| Trading fees | $348.66 (2026) | $0.00 | $111.45 direct / $56.51 terminal |
| Synthetic purchases | — | 1 initial + 40 flows | 1 initial + 40 flows |
| Owner contributions | $23,146 net | $23,146 net | $23,146 net |
| Rewards/income | ~$224 (Koinly) | $0 (staking is strategy-specific) | $0 |

> **Under the recovered static target allocation, continuous active rebalancing left the portfolio
> $4,067.53 ahead of passive holding.**

---

## Hard questions

**Q1. Can the static passive benchmark be computed without replaying Actual trades?**
**YES.** It is built from recorded snapshot NAVs, recorded owner-flow ledger rows, and recorded
prices. No trade is ever replayed. See §13.

**Q2. Does the 10.3% trade-replay defect affect any required benchmark input?**
**NO** — with one documented exception: production's own reported Actual NAV of $21,188.49 depends on
it. I substituted the independently recomputed recorded-snapshot NAV of $21,148.75. Using production's
figure instead moves the result by only $39.74.

**Q3. What exact starting capital is used?**
**$1,490.81** = $1,490.5632 USD + $0.2430 BTC, the in-scope NAV at 2025-12-05T17:00:56.973Z. Out-of-scope
holdings (PENDLE, MORPHO, XMR — $741.11, all liquidated within 27 seconds of inception) are excluded,
and carrying them as a sensitivity is the single largest uncertainty in the model.

**Q4. How are the Dec 9 $10,000 and later contributions treated?**
Allocated at the static weights using recorded prices at the event timestamp, **additively** — existing
units are never rebalanced. Non-USD contributions are economically converted into the static basket at
the same instant, so the passive strategy gets no better funding path than Actual did. Withdrawals
scale the book proportionally.

**Q5. Are existing passive units ever rebalanced?**
**NO.** Units are acquired once (or added to on a contribution) and are never modified afterwards.
Verified by the cohort structure and the terminal weight drift (passive BTC ended at 22.6% of the book
against a 24% target, purely from price movement).

**Q6. What passive transaction fees are charged?**
**$111.45** total, on the initial allocation and every subsequent crypto purchase, at the
notional-weighted realized rate of actual crypto-spot fills within ±3 days of each event (0.22%–0.57%
depending on the account's volume tier at that time). No fallback was needed. No fee on USD remaining
USD; no rebalance fees, because there are no rebalances.

**Q7. What is the fee-fair passive final NAV?**
**$17,081.22.**

**Q8. What is Actual − passive?**
**+$4,067.53 (+23.81%)** — Actual ahead.

**Q9. Which assets explain most of the difference?**
BTC +$1,219, SOL +$646, ETH +$533, PAXG +$497, XRP +$490, USD +$442 account for +$3,927 of the
+$4,067. Only **INJ (−$472)** and TAO (−$45) were detriments. The difference is systematic: Actual
accumulated the winners and cut the one laggard.

**Q10. Can the sign of the result change under reasonable uncertainty?**
**NO.** Across starting-capital treatment, fee rates from 0.20% to 0.5714%, XLM valuation, reward
treatment, and the choice of Actual NAV source, the result stays within **+$2,854 … +$4,109**.

**Q11. How do Koinly's +$1,051 realised gains coexist with the result?**
They are tax-basis accounting, not a performance measure. Actual realised taxable profits by selling
into strength — and on this account those sales were *good* decisions, because the assets sold
continued to appreciate. Actual ended $4,067 ahead of passive while realising gains, and paid 3.1× the
passive strategy's fees. Koinly's $355 fees and $224 income are already embedded in Actual's result
and are not subtracted again.

**Q12. Can we now answer the user's actual question?**
**YES.** "If I had chosen the same 12 assets and the same target weights, but bought once and held
instead of continuously rebalancing, would I have ended up with more or less money?" — **Less, by
$4,067.53 (−23.81%).** On this account, over 292 days, with $23,146 of net owner capital, continuous
active rebalancing was substantially more valuable than passive holding of the same configured basket.

Two qualifications that belong with the answer: this is one account over one period, and it is a
forensic computation rather than a shipped-engine result. It does not generalise to other portfolios or
periods, and it should be reimplemented and cross-checked inside the engine before it is relied on.
