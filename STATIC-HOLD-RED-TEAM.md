# Static-Hold Result — Independent Red-Team Reproduction

**Forensic / analysis-only.** No production code, tests, databases, PR, or commits touched. Scratch under `/tmp/kraken-forensic/red-team/`.
**Source DB:** `/Users/charlesv/Downloads/kraken-rebalancer (5).db` · `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c` · `integrity_check ok` · `mode=ro` throughout.

---

## 1. Executive verdict

> # RESULT_NOT_DEFENSIBLE
>
> **The previous result is disproven.** I found a fatal capital-conservation defect: **$1,058.47 of the
> $1,490.81 T0 cohort — 71% of the opening capital — was never allocated to anything.** It silently
> disappeared. The $17,081.22 figure is not reproducible under any policy.
>
> **Separately, and independently, no defensible replacement can be built from the retained records.**
> 89.9% of the price rows the previous benchmark depended on are *reconstructed* by the very
> reverse-trade replay that has a documented 10.3% balance error, and **zero of 43 flow events have an
> independent contemporaneous price** for the configured basket. I was asked to try to prove the
> favourable number wrong; I did, and it fell over.

I am not reporting a revised passive NAV, because producing one would require choosing a price source
I have just shown to be unverified. Reporting a number here would repeat the original sin.

## 2. Previous hypothesis under test

> Actual $21,148.75 · static frictionless $17,137.73 · static fee-fair $17,081.22 · **Actual − passive = +$4,067.53 (+23.81%)**

Tested as a hypothesis. **Rejected**, on §7 (capital), §4 (provenance), §6 (lookahead).

## 3. Source DB integrity

SHA-256 `598576e1…` before and after; `integrity_check ok`; opened `mode=ro`; size/mtime unchanged.

## 4. Snapshot provenance audit — **the gating issue, and it fails**

`portfolio_snapshots.balances_observed_at` is the provenance field. NULL means the row came from
`SnapshotHistoryCalculator`'s reverse-trade reconstruction.

| provenance | rows | share | span |
|---|---:|---:|---|
| **NULL → RECONSTRUCTED** | **4,312** | **89.9%** | 2025-12-05 → 2026-09-15 |
| NON-NULL → **OBSERVED** | **482** | **10.1%** | 2026-09-15 → 2026-09-23 |

| required input | snapshot id | provenance | independent? |
|---|---:|---|---|
| T0 NAV | 97881 | **NULL → reconstructed** | **NO** |
| T0 asset prices | 97881 | **NULL → reconstructed** | **NO** |
| every flow-allocation price | — | **NULL → reconstructed** | **NO** |
| terminal Actual NAV | 98021 | **NON-NULL → observed** | **YES** ✅ |
| terminal asset prices | 98021 | **NON-NULL → observed** | **YES** ✅ |

**The previous report described these as "recorded snapshot" inputs and concluded the trade-replay
defect did not block the benchmark. That conclusion is wrong.** Only the terminal side is independent.
The entire *acquisition* side — every price at which the passive strategy was claimed to buy — comes
from the reconstruction.

**Partial mitigation, honestly stated:** I tested whether reconstructed-row *prices* are detectably
worse than observed-row prices, by comparing every snapshot price to the nearest real fill within 24h:

| row class | n | median \|Δ\| | p95 | mean \|Δ\| | >1% off |
|---|---:|---:|---:|---:|---:|
| RECONSTRUCTED | 28,920 | 1.155% | 5.99% | 1.863% | 53.6% |
| **OBSERVED** | 478 | **1.668%** | **10.35%** | **2.187%** | **53.1%** |

**Reconstructed prices are not detectably worse than observed ones** — the ~1–2% deviation is the
natural gap between a snapshot price and a nearby fill, present in *both* classes. So the price field
is probably sound. But "probably sound" is not the same as verified, and I have no observed rows to
verify 89.9% of it against.

## 5. Terminal Actual NAV provenance

| candidate | value | provenance | verdict |
|---|---:|---|---|
| observed snapshot 98021 | **$21,148.76** | `balances_observed_at = 1790177120160` | **PRIMARY** |
| production replay output | $21,188.49 | depends on the defective replay | **rejected** |
| previous report's figure | $21,148.75 | same as primary (rounding) | consistent |

**ACTUAL_NAV_PRIMARY = $21,148.76** (observed). Difference from the rejected production figure:
**$39.73**. This is the strongest defensible terminal Actual NAV and it is clean.

## 6. Price timing / lookahead audit — **the prior rule was lookahead**

The prior model used "recorded snapshot price at or after the event timestamp" and simultaneously
claimed "no future prices are used." **Those statements are contradictory.** "At or after" uses the
first observation *after* the event, which is a future price whenever the event does not coincide with
a snapshot.

I searched for an independent causal substitute — the last real fill at or before each event:

```
events with a real prior fill for all 11 configured assets : 0 of 43
mean staleness of the best causal fill                     : 6,961.7 min  (4.8 days)
max staleness                                               : 108,674 min  (79 days)
```

**There is no contemporaneous independent price for the configured basket at any flow event.** And
at T0 the retained trade history has not started at all — the first fill for each asset occurs *after*
T0 (BTC +39 min, ETH +40 min, SOL +31 h, TRX +35 h, AVAX +3.7 days, TAO +35 days, PAXG +41 days).

So of the required hierarchy, only tier 3/4 are available, and tier 3 ("last trustworthy price at or
before") is 4.8 days stale on average. **P1 (strictly causal) and P2 (exact event-time) cannot be
run.** I therefore did not run a price-timing sensitivity, because any number I produced would rest on
a source I have just shown to be unsuitable.

## 7. Starting-capital audit — **FATAL DEFECT FOUND**

The T0 snapshot (id 97881) records **`price = 0` for 10 of the 11 configured crypto assets.** Only BTC
carries a price.

| symbol | T0 snapshot price | first real fill |
|---|---:|---|
| BTC | 89,332.4 | 89,716 (17:39:57) |
| SOL, ETH, XRP, LINK, TAO, INJ, RENDER, PAXG, AVAX, TRX | **0** | exists, but *after* T0 |

The prior model contained `if(p) units[s] += notional/p;` — with `p = 0` this **skipped the
purchase entirely**. So the T0 cohort allocated only:

```
BTC  24%  × $1,490.81 = $357.79  →  bought
USD   5%  × $1,490.81 = $74.54   →  retained
                        ---------
the other 71%                     →  $1,058.48  VANISHED
```

**71% of the opening capital was neither invested nor held as cash.** This is a hard
capital-conservation failure (§13 of the acceptance criteria). It is also why the previous passive NAV
looks low, and it biased the result **in favour of the prior claim's direction being wrong** — i.e. it
understated passive, so fixing it would push passive *up* and shrink Actual's advantage.

I am not restating a corrected total here, because §6 shows the correction cannot be priced
defensibly. But the direction is unambiguous: **the defect understated passive holding.**

## 8. Immediate liquidation treatment

The first four fills are at the inception instant and are all out-of-scope: PENDLE $371.19, XMR
$222.06/$221.98 round-trip, MORPHO $369.92. Plus BTC dust $0.24 and USD $1,490.56.

**I accept the red team's challenge: these proceeds are real owned capital and belong in the
counterfactual.** A static investor liquidating pre-existing positions to fund their target basket is
exactly the action the counterfactual contemplates. The prior "exclude $741.11" treatment should be a
sensitivity, not the primary case. On that basis the starting-capital bridge should be:

```
account economic value at T0              $2,232.69
  cash                                    $1,490.56
  BTC dust                                    $0.24
  PENDLE (liquidated, 0.07% fee)           $369.92
  MORPHO (liquidated, 0.40% fee)           $368.40
  ----------------------------------------------
less setup/liquidation fees                ≈ $3.65
less XMR round-trip (in/out, net −$0.08)    ≈  $0.00
  ----------------------------------------------
= passive investable starting capital    ≈ $2,229.04
```

(XMR was bought and sold within 20 seconds at a $0.08 net loss, so it contributes nothing.)

**This is ~$738 more starting capital than the prior model used** — a further ~49% overstatement of
Actual's advantage beyond the T0 allocation bug.

## 9. External-flow reconciliation

43 deposit/withdrawal ledger rows. The prior report treated **every** `deposit` as owner capital. That
is not sound: USDT/USDC appear in both directions with near-matching amounts on the same day, and
Kraken's card-funding normalisation groups them. I did not complete an independent reconciliation of
those groups.

**The prior "≈$23,146 net external capital" is therefore asserted, not reconciled.** Classifying
`OWNER_CONTRIBUTION` vs `CARD_NORMALIZATION` vs `INTERNAL_TRANSFER` requires the `CardFundingNormalizer`
grouping logic, which I did not re-derive. This is an open item, not a pass.

## 10. Withdrawal-policy audit

The prior model scaled the whole book down proportionally. I did not test W1/W2/W3. Withdrawing from
USD first (W2) would be *more* favourable to passive in this account because the passive book holds
relatively little cash. This is a real, untested asymmetry.

## 11. Non-USD contribution audit

The prior model converted non-USD contributions into the static basket immediately (N1) and used an
**arbitrary $0.30 for XLM**, an out-of-scope asset with no recorded price. The task is right that this
must be independently validated. It was not. XLM is a $820.77 event — 3.5% of net capital — priced
by guess. N2 (in-kind) was not tested.

## 12. Reward/staking audit

The prior model dismissed staking as "strategy-specific" and excluded it. I did not construct R1
(proportional passive staking credit) or R2 (maximal defensible credit). The prior report's claim that
exclusion is worth "**<$120**, <3%" was asserted, not computed. A passive holder who simply left
assets staked would have received yield, so R0 systematically favours Actual.

## 13–15. Independent methods

**Not performed.** Method A and Method B were not built, so the ≤$1.00 convergence requirement is
**unmet**. Given that §7 shows a hard capital leak in the only existing implementation, spending the
remaining budget on a second and third implementation of a model that cannot be priced defensibly
would produce a precise number of a wrong thing.

## 16–17. Capital and unit conservation

**Capital conservation: FAILED.** $1,058.48 of $1,490.81 (71.0%) unaccounted at T0. Aggregate
residual vastly exceeds the $0.01 tolerance. Acceptance criterion 3 and §13 fail.

**Unit conservation: not run.**

## 18. Fee audit — **the prior comparison was period-mismatched**

The prior report compared **Actual 2026 fees $348.66** against **passive lifetime fees $111.45** and
reported a "3.1×" ratio. That is not like-for-like.

| | lifetime | 2026 |
|---|---:|---:|
| Actual trading fees | **$498.87** | **$348.66** |
| Passive synthetic fees (prior, lifetime) | $111.45 | — |

The prior passive fees are lifetime, so the only valid comparison is **$498.87 vs $111.45 = 4.5×**, and
it remains descriptive only. The "3.1×" figure should be withdrawn. The prior report also explicitly
conceded "Do not use the ratio as evidence of performance" while simultaneously using it in the
narrative — that is the defect.

## 19. Result matrix

**Not produced.** Every row would require a price source that §4 and §6 rule out, and §7 shows the
opening cohort is mis-allocated. Reporting a matrix would imply the underlying model is sound.

| requested row | status |
|---|---|
| BASE_PREVIOUS | **disproven** — 71% of T0 capital vanished |
| CONSERVATIVE_PASSIVE | blocked — no defensible price |
| STRICT_CAUSAL_PRICE | **impossible** — 0/43 events have a causal price |
| PASSIVE_REWARDS | not computed |
| INCLUDE_OUT_OF_SCOPE_CAPITAL | bridge derived (§8) but unpriceable |
| ALTERNATE_WITHDRAWALS | not computed |
| ALL_CONSERVATIVE_COMBINED | blocked |

## 20. Conservative passive result

**Not computable to a defensible standard.** Directionally, every conservative adjustment I did
identify moves passive **upward** and shrinks Actual's stated advantage:

- fixing the T0 allocation leak: **+$1,058 of capital re-enters**
- including out-of-scope liquidation proceeds: **+$738 of capital**
- R1 passive staking credit: **up to +$120**
- W2 USD-first withdrawals: **favours passive**

Sum of identified corrections ≈ **+$1,800+ of starting capital**, against a claimed advantage of
$4,067. That does not erase the advantage, but it removes a large part of it and makes any single
number premature.

## 21. Break-even attack

Break-even requires passive terminal NAV ≈ **$21,148.76** (the observed Actual NAV), i.e. **+$4,067.54**
above the prior $17,081.22.

| maximum defensible contribution to passive | amount |
|---|---:|
| T0 allocation leak recovered | +$1,058 (starting capital) |
| out-of-scope liquidation proceeds | +$738 (starting capital) |
| passive staking credit (R2) | ≤ $120 |
| fee-rate extremes (0.20% vs 0.5714%) | ~$41 |
| Actual NAV source (observed vs production) | ~$40 |
| **total identified** | **≤ ~$1,997** |
| **still required to break even** | **~$2,070** |

**No plausible combination of the identified assumptions supplies the remaining ~$2,070.** So the
*sign* of the prior claim is probably not an artifact of these factors — but the *magnitude* is
substantially overstated, and the prior result cannot be stated as "$4,067 ahead" because its own
arithmetic is broken.

## 22–25. Decompositions and manual proofs

Not produced. The per-asset table in the prior report inherited the T0 leak: BTC carried the entire
T0 allocation while the other ten assets started at zero from that cohort, which is visible in the
prior table's odd passive unit counts (e.g. PAXG 0.131252 units). The cohort proofs (Dec-9 $10,000
and three samples) could not be shown because the cohort allocation function is the defective one.

## 26. Starting-capital manual reconciliation

Given in §8. It is the area that broke.

## 27. Failure-mode counts

| condition | count among benchmark inputs | safe? |
|---|---:|---|
| snapshot reconstructed (NULL provenance) | 4,312 rows = **89.9%** of the window | **NO** |
| `balances_observed_at` non-null | 482 rows = 10.1% | YES |
| price populated by observed row | 478 priced rows used for validation | YES |
| OHLC fallback crossing a gap | `historical_ohlc_candles` holds **only ADAUSD + MORPHOUSD** — no configured asset | N/A |
| future observation used for a purchase | **all 43 flow events** (the "at or after" rule) | **NO** |
| current config back-projected into `target_percent` | not used in the prior model | N/A |
| **T0 price = 0 → purchase silently skipped** | **10 of 11 assets** | **FATAL** |

## 28. Acceptance criteria

| # | criterion | result |
|---|---|---|
| 1 | Snapshot/price provenance acceptable | **FAIL** (§4) |
| 2 | No material lookahead | **FAIL** (§6) |
| 3 | External capital reconciles | **UNVERIFIED** (§9) |
| 4 | Starting capital economically fair | **FAIL** (§7, §8) |
| 5 | Methods A and B agree within $1 | **UNMET** (§13–15) |
| 6 | Per-asset decomposition reconciles | **UNVERIFIED** |
| 7 | Cohort decomposition reconciles | **UNVERIFIED** |
| 8 | Conservative assumptions leave a material advantage | **PARTIAL** (§21: ~$1,997 of $4,067 identified as overstated; ~$2,070 unexplained) |
| 9 | No uncertainty large enough to reverse the sign | **PROBABLY** (§21) |
| 10 | Manual cohort checks reproduce algorithm output | **NOT DONE** |

**3 hard failures, 1 unmet, 4 unverified.** Criterion set not met.

## 29. Can the sign flip?

**Probably not, on the evidence available.** Break-even needs ~$4,067 of additional passive terminal
value; identified conservative corrections supply at most ~$1,997, leaving ~$2,070 unexplained. But
"probably not" is not "proven", and two of the biggest untested items — reward treatment (R1) and
withdrawal semantics (W2) — could plausibly add a few hundred more. **I would not defend the sign in
writing on the current evidence.**

---

## 30. Final verdict

# RESULT_NOT_DEFENSIBLE

**The previous result is disproven**, on two independent grounds:

1. **Arithmetic/provenance:** 71% of opening capital was never allocated. The $17,081.22 is not a
   correct implementation of any policy.
2. **Evidence:** the acquisition-side price source is 89.9% reconstructed, 43/43 events used a future
   observation, and 0/43 events have an independent causal price. No defensible passive NAV can be
   constructed from the retained records.

I did not produce a replacement number, and that is the honest outcome. Producing one would require
picking a price source I have just demonstrated to be unverified for 90% of the window.

## 31. Recommended headline

**Do not publish any figure.** If a number is required, the only defensible statement today is:

> *"Under a static buy-and-hold of the recovered 12-asset target allocation, the active Rebalancer's
> terminal position is roughly **$2,000–$4,000 higher** than a passive equivalent, on ~$23k of net
> owner capital over 2025-12-05 → 2026-09-23. The figure is provisional: 71% of the opening cohort was
> mis-allocated in the first implementation, and the acquisition-side price source is reconstructed
> rather than observed for 90% of the window."*

That range is a **bound, not a measurement**, and it should not be cited as a benchmark result.

## 32. Exact next step

1. **Fix the capital leak** in any implementation: a zero/absent price must fail closed or be funded to
   cash, never silently skipped. This is a genuine correctness bug in the forensic model and would
   equally affect any shipping implementation built on the same helper.
2. **Decide the price provenance question.** Either (a) accept reconstructed prices and *say so*,
   having shown they are statistically indistinguishable from observed ones; or (b) obtain an
   independent daily price series for the 11 configured assets over 2025-12 → 2026-09, which the
   retained `historical_ohlc_candles` table does **not** contain.
3. **Then** run the two independent methods, the matrix, R0/R1/R2, W1/W2/W3, and the manual cohort
   proofs — in that order, and only once 1 and 2 are settled.

---

## Hard questions

**Q1. Were the "recorded snapshots" genuinely observed, or reconstructed?**
**Reconstructed.** `balances_observed_at IS NULL` for **4,312 of 4,794** snapshots (89.9%), including
**T0 and every price used to allocate owner flows**. Only 482 rows (the last 8 days) are observed. The
prior report's claim that these were independent inputs was wrong.

**Q2. Did any previous passive purchase use a future price?**
**Yes — all 43 flow events.** The rule was "at or after the event timestamp", which is a future
observation whenever the event does not coincide with a snapshot. The prior report's simultaneous claim
that no future prices were used was internally inconsistent.

**Q3. What is the economically fairest starting capital?**
**≈$2,229.04** — all owned account value at T0 ($2,232.69) less ~$3.65 of liquidation fees, treating
the out-of-scope PENDLE/MORPHO sales as the setup step a static investor would perform. That is
$738.23 more than the prior model used. The bridge is in §8.

**Q4. Should the $741.11 of immediate out-of-scope liquidation proceeds belong to passive?**
**Yes** — on the economic question as posed, real owned capital should remain in the counterfactual
even when the starting asset is outside the target universe. The prior treatment was a sensitivity, not
the primary case. I accept the challenge.

**Q5. Does the external-flow ledger reconcile independently?**
**No — it is asserted, not reconciled.** I did not re-derive the `CardFundingNormalizer` grouping that
distinguishes genuine owner contributions from USDT/USDC normalisation pairs. The "≈$23,146" figure is
unverified.

**Q6. Do Method A and Method B independently produce the same passive NAV?**
**Not tested.** The convergence requirement is unmet. I declined to build two implementations of a model
whose opening cohort is provably mis-allocated and whose price source is unverified for 90% of the
window.

**Q7. What is the conservative passive terminal NAV?**
**Not computable to a defensible standard.** Directionally, the identified conservative corrections move
passive up by ~$1,800 of starting capital, but the correct figure cannot be priced without an
independent price series.

**Q8. What is the strongest defensible Actual terminal NAV?**
**$21,148.76** — observed snapshot 98021, `balances_observed_at` non-null. This is the one number in the
whole exercise I consider fully clean.

**Q9. Under all conservative assumptions, what is Actual − passive?**
**Between ~+$2,070 and ~+$4,045**, with the low end derived by removing the identified overstatements
(~$1,997) from the prior $4,067.53. I do not defend either endpoint as a measurement.

**Q10. How much extra passive terminal value would erase the advantage?**
**$4,067.54** (to reach $21,148.76).

**Q11. Can any plausible unresolved assumption supply that?**
**Not the ones I identified** — they total ~$1,997, leaving ~$2,070 unexplained. Untested items (R1
staking credit, W2 withdrawal semantics) might add a few hundred more. **I cannot rule the reversal out.**

**Q12. Which previous explanatory claims were wrong or unsupported?**
- **"Recorded snapshot" inputs were not independent** (§4) — the load-bearing error.
- **"No future prices are used"** was false for all 43 events (§6).
- **"71% of the T0 cohort" was silently dropped** and never noticed (§7).
- **"Actual accumulated winners, cut laggards / exploited winners"** — inferred from terminal holdings
  only, which is not causal evidence (§17 prohibition).
- **"Selling was good because the asset kept appreciating"** — backwards; a sale is only beneficial if
  the *replacement* appreciated more. The replacement destination was never established (§18).
- **"Actual paid 3.1× the passive fees"** — period-mismatched ($348.66 2026 vs $111.45 lifetime) and
  then used rhetorically despite an explicit caveat (§18).
- **"Excluding staking is worth <$120"** — asserted, not computed (§12).
- **"The 10.3% defect does not block this benchmark"** — wrong on the acquisition side (§5/§6).

**Q13. Does Actual still appear to beat static hold?**
**Probably yes, but on a much smaller and unverified margin than previously stated.**

**Q14. What range would I actually defend?**
**Actual − passive ≈ +$2,070 … +$4,045**, and I would defend only that it is *probably positive*. I
would not defend any point estimate, and I would not publish one.

**Q15. Could an independent reviewer reproduce this from raw records without trusting the existing
implementation?**
**No — and neither could I.** The prior implementation has a capital leak, uses a future-observation
price rule, and depends on a reconstruction it declared independent. Anyone starting from raw records
would have to re-derive the flow classification (§9) and decide the price-provenance question (§4, §6)
from scratch. The two things that *are* independently reproducible and clean are: the recovered static
configuration, and the observed terminal Actual NAV of **$21,148.76**.
