# Static Hold V2 — Independent Price & Capital-Flow Recovery

**Analysis-only.** No production code, tests, databases, PR, or commits touched.
**Source DB:** `kraken-rebalancer (5).db` · `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c` · `integrity_check ok` · `mode=ro`.
**Scratch:** `/tmp/kraken-forensic/static-hold-v2/`

---

## 1. Executive verdict

> ## EVIDENCE_INSUFFICIENT
>
> **GATE A: PASS.** An independent, exchange-native, gap-free daily price series was obtained for all
> 11 configured crypto assets covering the full window. This *resolves* the price blocker that made the
> previous result indefensible.
>
> **GATE B: FAIL.** External owner capital cannot be independently reconciled. The raw ledger cannot be
> grouped by `refid` — all 43 rows carry distinct refids — so the card-funding / stablecoin
> normalisation pairs cannot be collapsed from retained evidence alone, and I have no independent price
> for the USDT and USDC legs.
>
> **Per §3 and §19 of the task, I stopped before computing any passive terminal NAV.** The T0 price
> problem is solved; the capital-flow problem is not.

The prior `+$4,067.53` was not resurrected, re-derived, or nudged. No passive NAV is reported here.

## 2. Source integrity

| item | value |
| --- | --- |
| Source DB SHA-256 before/after | `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c` ✅ |
| `PRAGMA integrity_check` | `ok` |
| Access mode | `mode=ro` only |
| Working tree | unchanged from prior passes; HEAD `35f19587`; nothing staged or committed |

## 3. Configuration input

BTC 24 · SOL 15 · ETH 15 · XRP 7 · LINK 7 · TAO 6 · USD 5 · INJ 5 · RENDER 5 · PAXG 5 · AVAX 3 ·
TRX 3 (Σ = 100). Provenance: inception configuration fingerprint == app-recomputed fingerprint of the
current `rebalancer-config.json` (exact SHA-256 match). Unchanged; not re-derived from prices,
holdings, `target_percent`, or outcomes. **The red-team T0 allocation bug is irrelevant to this input.**

## 4. Independent price-source discovery

| tier | source searched | result |
| --- | --- | --- |
| A | Kraken public OHLC (`api.kraken.com/0/public/OHLC`, `interval=1440`) | **AVAILABLE — 11/11 assets** |
| A | local `historical_ohlc_candles` (5,112 rows) | only `ADAUSD` + `MORPHOUSD` — **no configured asset** |
| A | `historical_ohlc_fetches` (466) | metadata only, no candles |
| — | any other local price table | none |

Kraken is the same venue as the account's own fills, so the series is **exchange-native** and
**independent of `SnapshotHistoryCalculator`**.

## 5. Price coverage by asset

| asset | Kraken key | pair | candles | first | last | daily gaps | raw artifact | SHA-256 |
| --- | --- | --- | ---: | --- | --- | --- | --- | --- |
| BTC | XXBTZUSD | XBT/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_BTC.json` | `8b52ebe555d9…` |
| SOL | SOLUSD | SOL/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_SOL.json` | `9ccc23d4ad7e…` |
| ETH | XETHZUSD | ETH/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_ETH.json` | `6730b2b17477…` |
| XRP | XXRPZUSD | XRP/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_XRP.json` | `576e43b13063…` |
| LINK | LINKUSD | LINK/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_LINK.json` | `1c35c6afa365…` |
| TAO | TAOUSD | TAO/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_TAO.json` | `b1d859f82c47…` |
| INJ | INJUSD | INJ/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_INJ.json` | `927eee5137ed…` |
| RENDER | RENDERUSD | RENDER/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_RENDER.json` | `442feecdca57…` |
| PAXG | PAXGUSD | PAXG/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_PAXG.json` | `262fae2219e8…` |
| AVAX | AVAXUSD | AVAX/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_AVAX.json` | `465715df303a…` |
| TRX | TRXUSD | TRX/USD | 295 | 2025-12-03 | 2026-09-23 | **0** | `ohlc_TRX.json` | `9df80b9953cc…` |

- Granularity: **daily**. Timezone: **UTC** (Kraken candle boundaries at 00:00Z). Adjustment: **none**
  (raw spot). Merging: **none** — one source, one series per asset.
- Coverage of the required window (2025-12-05 → 2026-09-23): **complete, zero missing intervals.**

## 6. Causal price policy

**Declared before any result is computed**, per §6:

> **Primary:** the close of the last **completed** daily candle whose interval ends at or before the
> event timestamp T. A candle for day D is complete at D+1 00:00Z, so it is admissible only if
> `candle_start + 24h <= T`.

This is strictly causal: it never uses an observation covering time at or after T. The alternative
— the candle *containing* T — is **rejected for the primary case** because for T0
(2025-12-05T17:00:56Z) it would consume 17 hours of future data.

Known limitation, stated rather than hidden: daily granularity means the primary price can be up to
**~24 hours stale** relative to the event. That is a precision loss, not a lookahead, and it is
quantified in §7.

## 7. Price uncertainty bounds

- **Primary:** last completed daily close at/before T (§6).
- **PASSIVE_FAVORABLE_BOUND:** the **low** of the last daily candle starting at/before T — a lower
  purchase price buys more units, which favours passive.
- **PASSIVE_UNFAVORABLE_BOUND:** the **high** of that same candle — favours Actual.

No price from a later unrelated period is used. Bounds are computed per event per asset, not applied
globally.

## 8. Reconstructed-vs-independent price comparison

**Not performed.** Doing it would have required re-deriving the reconstructed series as a comparable
panel, and per §8 the comparison is explicitly **not** allowed to retroactively legitimise the
invalid benchmark. The value of this comparison is now only diagnostic for a *future* fallback
decision, and it is not a gate.

## 9. T0 independent prices — **the previous blocker, now solved**

T0 = 2025-12-05T17:00:56.973Z. Last completed daily candle = 2025-12-04 (a ~17 h stale but strictly
causal observation):

```text
BTC 92,142.60   SOL 139.04   ETH 3,133.94   XRP 2.09615   LINK 14.26088
TAO    290.43   INJ   5.797  RENDER 1.712   PAXG 4,215.19  AVAX 14.41
TRX      0.286256
missing: none
```

**All 11 configured assets are priceable at T0 from an independent exchange-native source.** The
previous fatal defect (price = 0 for 10/11 assets) is resolved. Per §9, the correct handling of a
missing price is now moot for T0 — and any future implementation must fail closed or hold cash, never
silently skip.

## 10. Starting-capital bridge

Full-account value at T0 comes from snapshot 97881: **$1,861.21**. The immediate setup trades are
PENDLE and MORPHO (both out of the configured universe) plus an XMR buy/sell round trip.

| line | amount |
| --- | ---: |
| In-scope capital at T0 (USD $1,490.5632 + BTC dust $0.2430) | $1,490.81 |
| PENDLE liquidation proceeds | +$371.19 |
| MORPHO liquidation proceeds | +$369.92 |
| XMR round trip (buy $222.06 / sell $221.98) | −$0.08 |
| less PENDLE liquidation fee | −$1.4848 |
| less MORPHO liquidation fee | −$1.4797 |
| **passive investable starting capital** | **≈$2,228.96** |

> **Correction to my own earlier working figure.** An intermediate run reported liquidation proceeds
> of **$963.09**, which double-counted the XMR *purchase* as a source of proceeds. The XMR leg is a
> 20-second round trip at a $0.08 net loss and contributes nothing. The correct figure is
> **$741.11 gross, ≈$2,228.96 net of fees** — consistent with the red-team bridge, independently
> re-derived here.

## 11–13. External-flow ledger, normalisation, reconciliation — **GATE B FAILURE**

Raw evidence: **43** `deposit`/`withdrawal` ledger rows.

**Attempted grouping by `refid`:** all **43 rows carry a distinct refid** — 43 groups for 43 rows.
**Zero rows collapse.** Therefore:

- Card-funding / stablecoin normalisation pairs (e.g. `+USDT 2,752.72` on 2025-12-09 followed by
  `−USDT 999.35`, `−1,550.00`, `−205.24` the same afternoon, each with its own refid) **cannot be
  collapsed from retained evidence alone.** They are the same economic funding event moved between
  forms, and counting them as independent capital flows inflates the total.
- Valuing every row independently is also wrong for the same reason, and summing raw amounts across
  mixed currencies is worse still: the raw signed sum is **$44,673.64**, which is meaningless because
  it adds USD, USDT, USDC, XRP, XLM, RENDER, TAO and INJ as if they were all dollars.

**The blocker, precisely:**

1. **No independent price for the stablecoin legs.** I obtained exchange-native series for the 11
   configured assets only. USDT and USDC appear in 13 of the 43 flow rows and would need their own
   independent series to value any reconciliation. Forcing them to $1.00 is exactly the class of
   assumption §15 forbids without depeg evidence.
2. **The normalisation grouping is not recoverable from the ledger.** `CardFundingNormalizer` applies
   heuristics (amount/time proximity, currency class) that are not persisted. Re-deriving them
   independently means re-implementing the production heuristic — and the task explicitly says to
   *understand* it rather than blindly call the helper, while also not inventing a replacement.
3. **The prior `≈$23,146` is therefore asserted, not reconciled.** I can neither confirm nor refute it
   from retained evidence.

**Gate B cannot be closed.** The acceptance criterion — "every material external-flow row classified,
grouped normalisation legs reconcile, no internal transfer counted as new capital" — is not met.

## 14–17. Policies that could not be finalised

- **Non-USD contributions (§15):** cannot value XRP/XLM/USDC/USDT/TAO/INJ/RENDER legs without the
  flow reconciliation and independent stablecoin prices.
- **Withdrawal policy (§16):** W1/W2/W3 not tested. Note the W2 (USD-first) preference is
  *incompatible* with the unresolved question of **which currency each withdrawal actually consumed** —
  the USDT withdrawals suggest the owner was drawing down stablecoin, which is a different economic
  event from drawing down USD.
- **Reward policy (§17):** R0/R1/R2 not constructed. R1 requires scaling staking yield by
  `passive units / Actual units`, which needs reliable per-asset Actual holdings over time — i.e. the
  reconstructed balance series whose reliability is exactly what is in dispute.
- **Fee policy (§18):** passive fees are computable (initial + contributions + any withdrawal
  liquidation) and Actual lifetime ($498.87) and 2026 ($348.66) figures are already verified. The
  period-matched passive figure is not, because the passive capital events are not final.

## 18. Price gate result

> ## PRICE_GATE: **PASS**
>
> 11/11 assets, exchange-native daily OHLC, 295 candles each, 2025-12-03 → 2026-09-23, **zero gaps**,
> strictly causal convention declared in advance, T0 priceable for all 11 assets.

## 19. Flow gate result

> ## FLOW_GATE: **FAIL**
>
> 43 external-flow rows carry 43 distinct refids; no normalisation grouping is recoverable; no
> independent stablecoin prices; the net owner external capital figure is unverifiable. **This is a
> material unresolved amount** — flows are ~$23k against a ~$2.2k starting base, so a misclassification
> of a few rows is economically large.

**Per §3 and §19, I stopped here.** No passive terminal NAV was calculated. No Method A, Method B,
convergence test, capital/unit conservation, manual cohort proof, break-even analysis, or per-asset
return table is reported — all of them require a reconciled capital base that does not exist yet.

## 20–37. Not performed

Sections 20–37 of the requested report (Methods A/B, convergence, conservation checks, manual proofs,
result, bounds, break-even, per-asset tables, Koinly, final number) are **deliberately omitted.**
Producing them would mean inventing the capital base.

## 38. Recommendation

> ## Do not publish any static-hold number yet
>
> One of the two blockers is now cleared and the other is not. The honest position is
> **`EVIDENCE_INSUFFICIENT`** — not a range, not a bound, not a provisional figure.

## 39. Exact next step

Close GATE B, in this order:

1. **Fetch independent daily series for USDT and USDC** (Kraken `USDTZUSD`/`USDGUSD` or an
   equivalent reputable series) covering the window. This is the same work that just succeeded for the
   11 crypto assets and removes blocker (1).
2. **Persist or reconstruct the normalisation grouping.** Either (a) expose the `CardFundingNormalizer`
   grouping decision in the ledger or metadata so grouping is recoverable later, or (b) if it cannot be
   persisted going forward, hand-derive the 2025-12 → 2026-09 groups now from the retained rows using
   documented heuristics, and record the heuristic as an explicit, reviewable assumption.
3. **Re-run GATE B** against the §14 acceptance criteria. Only then run Methods A and B, the
   convergence test, the conservation checks, and the manual proofs — in that order.

---

## Hard questions

**Q1. Did you obtain an independent historical price series for every configured asset?**
**YES** — all 11, from Kraken's public OHLC endpoint, exchange-native, saved with SHA-256.

**Q2. What is its granularity and source?**
**Daily (1440 s) candles, UTC, unadjusted spot, Kraken `/0/public/OHLC`**, 295 per asset, zero gaps,
2025-12-03 → 2026-09-23. Raw artifacts in `/tmp/kraken-forensic/static-hold-v2/ohlc_*.json`.

**Q3. Can every capital event be priced causally without a future observation?**
**YES** — under the declared rule (last *completed* daily close at/before T). The residual issue is
precision, not causality: the price may be up to ~24 h stale.

**Q4. How closely do independent prices agree with reconstructed application prices?**
**Not measured**, deliberately (§8). It cannot retroactively legitimise the invalid benchmark.

**Q5. What is the exact economically defensible T0 starting capital?**
**≈$2,228.96** — $1,490.81 in-scope + $741.11 out-of-scope liquidation proceeds − $2.9645 liquidation
fees. This includes the PENDLE/MORPHO proceeds, which the red team correctly argued belong to the
counterfactual.

**Q6. Did every dollar of T0 capital get allocated or retained?**
**Not applicable — no allocation was performed**, because GATE B failed first. The T0 *pricing* blocker
is resolved; the *capital-reconciliation* blocker is not, and a T0 proof that depends on a reconciled
capital base would be circular.

**Q7. What is the independently reconciled net owner external capital?**
**Unknown.** Not $23,146 (asserted, unverified) and not $44,673.64 (the meaningless raw signed sum).
The true figure cannot be established from retained evidence as it stands.

**Q8. How much of the previous ~$23,146 was normalisation rather than true external capital?**
**Unquantifiable.** 13 of 43 rows are USDT/USDC legs that are strong candidates for normalisation. I
cannot size that without the grouping logic and stablecoin prices.

**Q9. Do Method A and Method B agree?**
**Not tested** — the methods were not built, per §3/§19.

**Q10. What is the primary passive final NAV?**
**Not computed.** Deliberately withheld.

**Q11. What is the most passive-favourable defensible final NAV?**
**Not computed.** Price bounds (§7) are defined and ready; without a capital base they cannot be
applied.

**Q12. Does even that passive-favourable NAV beat $21,148.76?**
**Unknown.** The previous +$4,067.53 is not resurrected and I have not produced a replacement.

**Q13. What is the defensible Actual − passive range?**
**None.** The only defensible statement today is that a range cannot be bounded until capital is
reconciled.

**Q14. Can the sign flip?**
**Unknown, and I decline to assert either way.** The last pass's break-even analysis assumed a
starting capital that I have since found to be understated by ~$738 and a capital base that is now
known to be unreconciled. Neither the sign nor the magnitude is currently supportable.

**Q15. Can an independent reviewer reproduce every dollar from raw evidence?**
**No.** Two things are now independently reproducible — the static configuration, and the observed
terminal Actual NAV of **$21,148.76**. Capital is not.

**Q16. Can we finally answer: "Did the active strategy beat static holding of the same configured
basket?"**
**NO — not yet.** The price evidence is now sufficient; the capital evidence is not. One blocker
remains, and it is addressable: obtain stablecoin price series and recover the funding-normalisation
grouping. I would rather return this than a number that fails independent reconstruction.
