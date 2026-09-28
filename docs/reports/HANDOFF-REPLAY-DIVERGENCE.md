# HAND-OFF: static-hold replay harness — reconcile Kotlin/Python divergence

**Date:** 2026-09-28
**Repo:** `new-kraken-rebalancer`
**Branch:** `fix/inferred-configuration-matched-hold` (stacked on PR #366, PR #367 open)
**HEAD:** `484640c0` — working tree **clean**, nothing uncommitted
**Goal of this task:** fix two defects in the replay harness and correct a documented recommendation that is now known to be wrong.

---

## 0. Read this first — the situation in one paragraph

A forensic audit established that the Rebalancer roughly **tied** buy-and-hold (Δ ≈ −$81, −0.4%) over 9.7 months, because it harvested a real rebalancing edge (+1.52%) but paid more in fees to collect it (−1.92%). Features were built to address this: a trend-aware sell suppression, quality scores, and a replay harness that runs the **production** `RebalancerEngine` against buy-and-hold on real history. The harness produced a settings sweep. A **second, independent Python re-implementation** of the same policy then produced **materially different numbers**, and the two implementations have **not been reconciled**. The documented recommendation (10% trigger) came from the Kotlin harness and is now **contradicted** by the better measurement. **Do not trust either number until the divergence is located.**

---

## 1. State of the world

### 1.1 Committed work (both pushed, CI green)

| Commit | Contents |
| :--- | :--- |
| `af22fc4d` | Trend-aware sell suppression; fiat funding provenance via `method_id`; quality scores + dashboard metrics + score-derived allocation preview; replay harness |
| `484640c0` | `docs/ALGORITHM.md` §4/§5 (recommended settings) + replay sweep test + CHANGELOG |

### 1.2 Files that matter

| Path | Role |
| :--- | :--- |
| `backend/src/test/kotlin/com/gemini/krakenbot/replay/ReplayComparator.kt` | **The harness.** Runs `RebalancerEngine` over a fixture. Has the `lastDay` bug (§4). |
| `backend/src/test/kotlin/com/gemini/krakenbot/replay/LocalReplayVariantsTest.kt` | Runs variants, the sweep, and the walk-forward. |
| `backend/src/test/kotlin/com/gemini/krakenbot/replay/LocalReplayFixtureTest.kt` | Simpler single-run fixture test. |
| `backend/src/test/kotlin/com/gemini/krakenbot/replay/ReplayComparisonTest.kt` | Harness self-tests (capital conservation etc.). |
| `engine/.../domain/RebalancerEngine.kt` | Production decision logic. **Assume correct.** |
| `engine/.../domain/PortfolioCalculations.kt` | `calculateAssetMetrics`, `calculateDeviationPercent`, `calculateTargetPercent`. |
| `docs/ALGORITHM.md` §4–5 | **Contains a now-questionable recommendation.** |
| `.replay-data/` | **gitignored** scratch: `build_fixture.py`, `replay_check.py`, `ohlc/raw_*.json`. |

### 1.3 Environment notes (do not re-litigate)

- **Local Gradle is broken for JS tasks.** `kotlinStoreYarnLock` fails because yarn 1.22.22 on Node 26 drops child-workspace `devDependencies` (proved with a 2-file repro). The browser-test toolchain then vanishes from a fresh resolve. **CI is green** because Linux CI resolves correctly. This is a **local-only, environment** issue — **not a repo defect, not yours to fix.** `yarn.lock` and `build.gradle.kts` are byte-identical to HEAD; leave them alone.
- **Consequence:** `./gradlew :backend:test` cannot run locally. Verification must be either (a) CI after pushing, or (b) the Python cross-check. Plan for this.
- **Source DB:** `kraken-rebalancer (5).db`, SHA-256 `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c`. Verify before and after. Open read-only.
- **Scratch was wiped once** by a machine reboot (`/tmp/kraken-forensic/`). Prefer paths inside the repo or re-generate.

---

## 2. The fixture

Rebuild with `python3 .replay-data/build_fixture.py` (needs the 14 Kraken OHLC files in `.replay-data/ohlc/`, re-fetchable from the public API, and the source DB). It writes `/tmp/kraken-forensic-replay-fixture.json`.

Contents: **293 days** (2025-12-04 → 2026-09-22), **50 flows**, net flow **$18,581.45**, opening capital **$1,899.422405588**, fee rate **0.0035**, 12-asset allocation matching the target config. The 11 crypto series have identical daily coverage.

The flows include the Framing-A treatment: the two STRC purchases (−$2,293.79, −$3,002.097) are treated as capital leaving the strategy, and the nine STRC dividends (+$334.56 total) as capital returning. **The STRC position is deliberately excluded from both arms** — it is not part of the target configuration and the operator asked for it to be out of the comparison. Removing capital from only one arm was the original error that produced a spurious −$7,265 "gap".

---

## 3. Known results (both implementations, same fixture)

### 3.1 Kotlin harness (`ReplayComparator`, via `LocalReplayVariantsTest`)

| Config | NAV | vs B&H | Fees | Trades | Suppressed |
| :--- | ---: | ---: | ---: | ---: | ---: |
| 5% trig, no tail-stop | 26,332.31 | −507.96 | 233.85 | 1,136 | 0 |
| 10% trig, 20d tail-stop | 26,519.65→ best cell reported −68.08 | | | | |

(The −68.08 figure came from the lookback×trigger sweep at trig=10, lookback=20.)

### 3.2 Python cross-check (`.replay-data/replay_check.py`)

| Config | NAV | vs B&H | Fees | Trades | Suppressed |
| :--- | ---: | ---: | ---: | ---: | ---: |
| A 5% trig, no tail-stop | 26,245.80 | −594.48 | 288.33 | **1,781** | 0 |
| B 5% trig, 20d tail-stop | 26,539.30 | −300.97 | 287.73 | 1,851 | 220 |
| C 10% trig, no tail-stop | 26,331.10 | −509.17 | 233.99 | 1,064 | 0 |
| D 10% trig, 20d tail-stop | 26,542.52 | −297.76 | 243.84 | 1,240 | 142 |
| E 10% trig, 30d tail-stop | 26,536.87 | −303.40 | 242.54 | 1,209 | 112 |
| **F 7% trig, 15d tail-stop** | 26,631.92 | **−208.36** | 267.15 | 1,559 | 215 |

**Buy-and-hold baseline: 26,840.27 (fees 140.29)** — both implementations agree on this, which confirms the flow schedule and pricing are fine.

### 3.3 The divergence

At the **identical** config (5% trigger, no tail-stop):

- Kotlin: 1,136 trades, Δ = **−507.96**
- Python: 1,781 trades, Δ = **−594.48**

A ~57% trade-count difference at the same settings. This is the blocker.

**Already ruled out:**

- Price data / fixture — identical (same buy-and-hold baseline to the cent).
- Flow schedule — identical.
- Trigger algebra — verified equivalent. Engine: `deviationUSD = current − target`, `deviationPct = deviationUSD/targetValueUSD × 100`, `targetValueUSD = total × calcTargetPercent/100`, `calcTargetPercent = baseTargetPercent × cryptoScaleFactor` (harness passes `cryptoScaleFactor = 1.0`, and `baseTargetPercent/100 == w`). Python uses `dev = vals[s] − total*w` and `abs(dev/target)*100 >= trigger`. **Algebraically identical.**
- `calculatePortfolioValues` failure path — returns `Result.Failure` only when a *configured* asset has a missing/zero price; every configured asset has a price every day, so this should never fire. (Worth confirming, not yet confirmed in the trace.)
- `USDT`/`USDC`/`XLM` — not in the target basket, so they cannot affect the plan.

### 3.4 Most likely suspects (unverified — check these first)

1. **Rounding / scale.** `calculatePortfolioValues` rounds per-asset values to `SCALE_USD` (2dp) *before* the plan, and the total to 2dp. Python works in full precision. Near the dust boundary this can flip a `|dev| ≥ minimumOrderSizeUSD` gate, cascading into different trade sets.
2. **Dust gate semantics.** Engine `isSignificant = |deviationUSD| ≥ minimumOrderSizeUSD` where `minimumOrderSizeUSD` is a `Double` (5.0) from the fixture. Python uses `Decimal(5)`. Check whether the engine also applies a *second* gate or a different rounding before the USD comparison.
3. **The `fiatCorrection` path.** `analyzeDeviationsPlan` runs `distributeFiatCorrectionPlan` **only when `buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered`**. Python has no equivalent branch. If USD triggers alone on some days, the two implementations can legitimately diverge there — and that would explain extra Python trades.
4. **Buy funding clamp.** The harness's `execute()` skips a buy when `affordable < usdAmount`; the real `OrderExecutorImpl` clamps to `min(cycleBuyBudget, actualCash)` and skips below `minimumOrderSizeUSD`. If the harness's `plan` ever produces a buy the book cannot fund, Python may still fill it.
5. **Trending set on non-rebalance days.** Verify the harness computes `trending` with the same `subList(from, day+1)` window as Python, and that `from = max(0, day − lookback + 1)`.

---

## 4. Defect 1 — `lastDay` baseline bug (confirmed, shipped)

`ReplayComparator.run(..., lastDay)` truncates the **rebalanced** arm to `lastDay` but the caller compares it against a buy-and-hold evaluated at the **full window end**. Comparing a day-146 NAV to a day-292 baseline is meaningless.

This produced the nonsense first-half figure of **−$6,400.59** in an earlier walk-forward output. Both the Kotlin `LocalReplayVariantsTest` and the Python script share the flaw (the Python version was already fixed in scratch — port that fix).

**Corrected numbers** (Python, both arms evaluated at the same end day):

| Window | B&H | 5%/none | 10%/20d | Improvement |
| :--- | ---: | ---: | ---: | ---: |
| first half (0–146) | 21,003.44 | −563.76 | −426.95 | **+136.81** |
| full window (0–292) | 26,840.27 | −594.48 | −297.76 | **+296.72** |

**Fix:** make `run()` evaluate (or accept) a matching buy-and-hold at the same `lastDay`. A test asserting this is needed — `ReplayComparisonTest` is the right home.

---

## 5. Defect 2 — the documented recommendation is wrong

`docs/ALGORITHM.md` §5 currently states **trigger 10, lookback 20** as recommended, citing −$68 from the Kotlin sweep. The Python cross-check gives **−$297.76** for that same cell, and finds a **better cell at 7% / 15d (−$208.36)**.

**The doc must not be changed until Defect 3 (§3) is resolved** — you do not yet know which implementation is faithful. Changing it now would be a second unfounded revision.

Once the divergence is resolved, either:

- if the Kotlin harness is correct, find and fix the Python error and re-verify; or
- if the Python is correct, fix the harness, then update the doc to the reconciled numbers.

Note the honest framing that must survive into any doc revision: **rebalancing trailed buy-and-hold in every configuration measured** (best ≈ −$208). The tail-stop is a **variance reducer** that reliably improves the outcome relative to no-tail-stop, **not** an edge that beats holding. The doc already says this — keep it.

---

## 6. Suggested order of work

1. **Instrument and locate the divergence.** Add a debug mode to both implementations that, for a chosen day, dumps: total value, per-asset current/target/deviationUSD/deviationPct, which assets triggered, the raw plan, and the fills. Run both on days 0–10 at 5%/no-tail-stop and diff. The first differing day is the answer. *(Bounded, mechanical — this is the unblocking step.)*
2. **Fix `lastDay`** (Defect 1) and add a regression test.
3. **Reconcile the two implementations.** Make them agree to the cent, or delete the less faithful one. The harness is supposed to be production-representative; the Python is a cross-check. If they cannot both be kept, say so in the report.
4. **Re-run the full sweep** on the reconciled implementation. Confirm whether the best cell is 7%/15d, 10%/20d, or something else, and re-check stability across both halves.
5. **Correct `docs/ALGORITHM.md` §5** and the CHANGELOG with the reconciled numbers, keeping the "variance reduction, not an edge" caveat.
6. **Verify and push.** CI is the only reliable local gate. Run `./gradlew check` in CI or trust the push. Never `kotlinUpgradeYarnLock` locally — it destroys ~1,600 lines of the lock (this happened once and was reverted; `yarn.lock` is currently clean).

---

## 7. Hard constraints

- **Preserve** all existing uncommitted work if any appears (tree is clean now; 17 pre-existing modified files were part of earlier commits — do not revert history).
- **Never** `kotlinUpgradeYarnLock` locally. Never hand-edit `kotlin-js-store/yarn.lock`.
- Verify the source DB hash before and after. Open it **read-only**.
- Repo rules: BigDecimal only (crypto scale 8, USD scale 2), `shouldBeEqualComparingTo` (never `.equals()`), no FQNs, 120-char lines, Spotless, `allWarningsAsErrors`, in-memory SQLite `:memory:`, Kotest `StringSpec` + `init {}` + `IsolationMode.InstancePerTest`, no ARIA attributes, no absolute user paths, reference `:common` catalogs.
- Run Gradle **serially** — concurrent `./gradlew` in one clone kills test workers.
- Prefer writing a defect's regression test **before** the fix.

---

## 8. What is already trustworthy

Do not re-derive these:

- **Audit conclusion:** Rebalancer ≈ buy-and-hold (Δ ≈ −$81, −0.4%) on the real account. `FLOW_GATE = PASS` under a reasoned standard; the 19 previously-UNRESOLVED USD deposits are now classified via `method_id` (defect B, shipped in `af22fc4d`).
- **Root causes of the loss:** rebalancing edge +1.52% was real but fee drag −1.92% exceeded it; 3,050 of 3,057 order legs paid taker fees (0.35%) instead of maker (0.01%); the tail-stop addresses the sell-into-trend tail and helps consistently in both halves (+$96 first half, +$187 full, on the 5%-trigger baseline).
- **The tail-stop mechanism** (`RebalancerEngine.analyzeDeviationsPlan(..., trendingAssets)`) and its safety property (suppressed sells reduce cash; the executor clamps buys to settled cash, so an order degrades rather than failing). Covered by `PortfolioManagerTrendSuppressionTest` and `RebalancerEngineTest`.
- **The replay harness design goal:** run the *production* engine, not a reimplementation, so results describe production behaviour. The Python script is a **cross-check only** and should be kept in scratch, not shipped as a parallel source of truth.

---

## 9. Honest failure modes of the previous agent (learn from these)

The prior agent in this session was wrong **five times** before this hand-off, and the user was told each time:

1. Claimed portfolio valuation "dropped" non-target assets (STRC) — it did not; scoping display to the managed basket is correct behaviour. Reverted.
2. Claimed `unallocated`-style bugs in the engine without reading `calculateDeviationPercent` first.
3. Mis-diagnosed the yarn/Node lock failure as (a) partial build state, (b) stale Gradle cache, (c) network, (d) a webpack resolution conflict — four wrong theories, each asserted confidently before testing. The real cause (yarn 1 drops child-workspace devDeps) was found with a 2-file minimal repro.
4. Ran `kotlinUpgradeYarnLock` **before backing up the file**, destroying 1,638 lines in the working tree. Reverted immediately and verified byte-identity, but it was avoidable risk taken on the user's repo.
5. Recommended a 10% trigger from a harness whose numbers a second implementation contradicts — exactly the failure this hand-off exists to prevent.

**Lessons:** verify by executing, not by reasoning; diff the two artifacts before theorising; back up before running any task documented to rewrite a file; and when two independent implementations disagree, treat the disagreement as the finding — do not publish either number.
