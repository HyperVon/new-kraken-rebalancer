# PR #367 — Re-scoping Report: Journal-Based Architecture

**Base head:** `35f195873975f87b0b17b7873f6d43a2710a760d` · nothing committed, nothing pushed, PR not modified.
**Source DB:** `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c` · `integrity_check ok` — re-verified, untouched.

---

## 1. Executive architectural recommendation

**Accept the direction. Do not implement it inside PR #367. Split it into three PRs (§20).**

The governing principle you stated is right and the evidence supports it decisively:
*configuration-matched hold must replay the operator's recorded investment configuration, not infer
that configuration from the portfolio produced by the strategy.* On this history, membership-only
inference resolves to **zero** additions and **zero** removals after any plausible bootstrap, because
the strategy rebalances weights continuously and never changes membership. There is nothing for the
benchmark to mirror. That is not a bug in the inference — it is proof that a weight-driven strategy
cannot be evaluated with a membership-driven model.

**One thing I did change this pass:** I removed the four economics pins added in the previous pass
(`$21,188.49 / $25,366.83 / −$4,178.34 / −16.4717%`) from `ConfigurationMatchedHoldAcceptanceTest`,
with a comment explaining why. They certified a definition now known to be wrong, and per your §20 a
green test is not a reason to keep a bad pin.

**What I did not do, and why:** I did not implement the journal. I have limited working budget left in
this session, and a schema with no write-path integration, or a benchmark method swap without the
journal behind it, would be exactly the dead complexity §11 warns against. Half-wired architecture is
worse than a precise plan. What follows is a complete, implementation-ready design plus the §19
inventory.

## 2. Current local-change inventory

| File | Δ | Classification | Rationale |
|---|---:|---|---|
| `backend/build.gradle.kts` | +35 | **KEEP** | `ACCEPTANCE_DB_PATH` + SHA-256 fingerprint as `:backend:test` task inputs. Fixes a real stale-cache defect, independent of the journal. |
| `…/history/BenchmarkEvent.kt` | +57/−? | **MODIFY** | `ConfigurationResetTurnover` (preValue/postValue/delta/cryptoSell/cryptoBuy/cashDelta) is **exactly** the D3 model §7 wants — keep it. The `ConfigurationReset` event's `additionFundingShares`/`configurationAllocation` fields are inference-shaped — replace. |
| `…/history/ConfigurationRegimeInference.kt` | +3 | **DELETE** | Only change is the `clusterStart` field added to support behavioral funding evidence. Inference itself is no longer an authoritative input. |
| `…/history/RebalancerComparisonCalculator.kt` | +586 | **MODIFY (large)** | Split: **KEEP** `ConfigurationResetTurnover`, `exactOne`, the atomicity fix, `canonical`, the settlement criterion *as a forensic concept*, and the `fundingResolvable` shared predicate. **DELETE** `observedInScopeValues`, `ClusterFundingEvidence`, `additionFundingShares`, `applyTransitionToAllocation`, `settledAnchorAtOrAfter`, `attachConfigurationAllocations`, and the whole `anchoredRegimes`/`epochWeightsAt` inference branch. **ADD** journal-driven revision rebalancing. |
| `…/ConfigurationInferenceSensitivityTest.kt` | +1 | **DELETE** | Tests the inference being removed. |
| `…/ConfigurationMatchedHoldAcceptanceTest.kt` | +7 | **MODIFY** | Economics pins removed this pass. The suite becomes the journal-backed benchmark's acceptance case and must pin *availability + provenance*, not a pre-journal economic figure. |
| `…/ConfigurationResetReplayTest.kt` | +853 | **MODIFY** | Zero-balance regression, NAV conservation, cash-leg separation, fail-closed behaviour and the D3 turnover assertions all **carry forward** to the journal-based model. The funding-evidence and settlement-anchor cases go. |
| `…/ForensicComparisonCacheIsolationTest.kt` | +1 | **KEEP** | `clusterStart` arg only. |
| `…/ForensicRegimeSeamTest.kt` | +1 | **DELETE** | Forensic seam exists only to feed supplied inferred transitions. |
| `AcceptanceCacheIdentityTest.kt` (new) | +3 tests | **KEEP** | Guards the build-level fix. |
| `ConfigurationResetEvidenceTest.kt` (new) | +23 tests | **MODIFY** | Keep the D3/NAV/fail-closed cases; drop the funding-hierarchy and settlement cases. |
| `ConfigurationSettlementAndAllocationTest.kt` (new) | +9 tests | **DELETE** | Entirely about the settlement criterion and inferred allocation state. |

**Survivors worth protecting:** `ConfigurationResetTurnover` (the D3 model), the zero-balance
regression, the shared `fundingResolvable`-style predicate, the atomicity fix, and the build-cache
input fix. That is a small, high-value residue from 945 inserted lines.

## 3. Proposed configuration-journal schema

Matching existing conventions (`repository/table/*.kt` Exposed objects; `CURRENT_SCHEMA_VERSION = 15`;
additive migrations; `chk_*_signed_integer_*` conventions; `ON DELETE CASCADE`).

```kotlin
// repository/table/PortfolioConfigurationRevisionTable.kt
object PortfolioConfigurationRevisionTable : Table("portfolio_configuration_revisions") {
    val id = integer("id")                       // signed-int convention
    val effectiveAt = long("effective_at")       // epoch millis; deterministic ordering
    val createdAt = long("created_at")
    val source = varchar("source", 32)           // provenance enum name
    val reason = varchar("reason", 256).nullable()
    override val primaryKey = PrimaryKey(id)
    init { index(false, effectiveAt, id) }       // revision lookup by time
}

// repository/table/PortfolioConfigurationTargetTable.kt
object PortfolioConfigurationTargetTable : Table("portfolio_configuration_targets") {
    val revisionId = integer("revision_id")
        .references(PortfolioConfigurationRevisionTable.id, onDelete = ReferenceOption.CASCADE)
    val symbol = varchar("symbol", 16)
    val targetWeight = decimal("target_weight", 18, 8)   // 0.0 .. 100.0 percent
    override val primaryKey = PrimaryKey(revisionId, symbol)   // duplicates impossible
    init { index(false, revisionId) }
}
```

Required properties, each with the mechanism that enforces it:

| Property | Mechanism |
|---|---|
| Weights sum to expected total | validated in the service before insert (`== 100.00%` over the configured allocation set, matching existing `Allocation` validation) |
| Revisions immutable | no `UPDATE` path exists; the repository exposes `insert` only |
| Edits create a new revision | `ConfigServiceImpl.updateConfig` always inserts; it never mutates |
| History survives edits | rows are never deleted except by explicit cascade from a revision that is itself immutable |
| Symbol normalization | `Asset.normalizeLedgerAsset(x).uppercase()` at the single insert point |
| Duplicate targets impossible | composite PK `(revision_id, symbol)` |
| Deterministic effective ordering | index on `(effective_at, id)`; lookup = highest `id` with `effective_at <= t` |

## 4. Configuration write-path integration

**There is exactly one write path:** `ConfigServiceImpl.updateConfig(newConfig)` (`ConfigServiceImpl.kt:81`),
which already validates, persists atomically via `writeConfigAtomically`, and publishes through
`publishOrStage`. Configuration is also reloaded on file watch, and `loadConfigBlocking` is the
startup path.

So the hook is: after `writeConfigAtomically(persistedConfig)` succeeds, journal a revision built
from `persistedConfig.allocations`. Three properties to honour:

- **Atomicity.** Journal within the same transaction as the config write if the DB is reachable;
  if the journal write fails, the config change must not silently succeed. Recommend: journal
  *before* publishing, and fail the update closed if journaling fails.
- **Settings-only changes must not journal.** `updateConfig` distinguishes credential rotation from
  allocation changes; a change that leaves `allocations` equal to the previous revision must not
  create a new revision. Otherwise every settings save adds a spurious benchmark rebalance.
- **Startup must not journal.** `loadConfigBlocking` runs on every boot and on file watch; it must
  seed nothing. Journaling belongs only in the deliberate-change path.

**Test that closes the bypass:** a test asserting that calling `updateConfig` with an
allocations-changed config inserts exactly one revision, with an allocations-identical config inserts
none, and that a revision is present for every revision returned by the journal.

## 5. Migration behavior

`SchemaMigration(16, "configuration-journal")` — **additive only**, no `DROP`, no data rewrite:

```
CREATE TABLE IF NOT EXISTS portfolio_configuration_revisions (...)
CREATE TABLE IF NOT EXISTS portfolio_configuration_targets (...)
CREATE INDEX IF NOT EXISTS idx_pcr_effective ON portfolio_configuration_revisions(effective_at, id)
```

Then bump `CURRENT_SCHEMA_VERSION` to 16. `validateSchemaMigrations` already enforces contiguous
versions, so no other change is needed. Existing databases migrate without data loss; the new tables
are empty until the first real configuration change or the journal-start seed (§6).

**No fabricated historical revisions.** The migration creates structure only.

## 6. Journal-start semantics

On first journal write (or an explicit one-shot seed at upgrade), insert a revision with
`source = CURRENT_STATE_AT_JOURNAL_START`, `effectiveAt = <journal start instant>`, and the
allocations configured at that moment. That revision is authoritative **from its `effectiveAt`
forward only**.

`journalStartTimestamp` = the `effectiveAt` of the earliest revision. Everything before it has
**unknown** configuration — not "inferred", not "assumed", unknown.

## 7. New benchmark semantics

`CONFIGURATION_MATCHED_HOLD` replays recorded revisions:

- **A. Routine market movement** — benchmark holds units. No drift rebalancing. No snapshot reading.
- **B. Owner contribution** — invest the new capital at the revision effective at (or before) the
  contribution timestamp. Never a later revision. Same `epochWeightsAt` shape, but sourced from the
  journal instead of `anchoredRegimes`.
- **C. Owner withdrawal** — keep existing neutral-flow (proportional) semantics. Document that a
  withdrawal does not force a synthetic liquidation; the benchmark's exposure simply shrinks pro rata.
  If you later want liquidation semantics, that is a separate, explicit decision.
- **D. Configuration revision** — rebalance **once**, from current synthetic values to the new
  target values, preserving NAV except for modelled costs. This is the `ConfigurationResetTurnover`
  machinery already written, with the target vector supplied by the revision instead of by
  `observedInScopeValueWeights`.
- **E. Rewards/income** — keep existing holding-dependent semantics.
- **F. Fees** — later, and only on `cryptoSellNotional` / `cryptoBuyNotional` from D, plus
  contribution deployment. Never on a full book, never on USD.

## 8. Same-membership weight change

```
Before:  BTC 30%  ETH 20%  SOL 10%
New rev: BTC 20%  ETH 25%  SOL 15%
```

`preValue` = current synthetic values. `targetValue = nav × newWeight`. Deltas BTC −, ETH/SOL +.
Minimum executable turnover = the net of those deltas. **No asset entered or exited, and the
benchmark still rebalances correctly** — which is precisely what the current membership model cannot
express. Membership becomes a special case: weight `>0 → 0` is a removal, `0 → >0` an addition.

## 9. Contribution behaviour

Unchanged in mechanism, changed in source: the allocation vector is the recorded revision effective
at the contribution timestamp, not an observed Actual weight vector. So contributions stop importing
Actual drift, transient zeros, and Actual's cash buffer — all three of which were real defects found
in the prior passes.

## 10. Configuration-transition turnover behaviour

`ConfigurationResetTurnover` is retained as-is. It already computes `preValue`, `postValue`, `delta`,
`cryptoSellNotional = Σ max(0, −deltaCrypto)`, `cryptoBuyNotional = Σ max(0, +deltaCrypto)`, and a
separate `cashDelta` for settlement capital. The test that BTC $5,000→$5,500 / ETH $5,000→$4,500
yields sell ETH $500 / buy BTC $500 (not $10,000/$10,000) is already in `ConfigurationResetReplayTest`
and carries forward unchanged.

## 11. Pre-journal availability behaviour

New reason, distinct from `HISTORICAL_COVERAGE_GAP` (which means price coverage, a genuinely
different failure):

```
CONFIGURATION_HISTORY_UNAVAILABLE
```

Rules:
- requested interval begins **before** `journalStartTimestamp` → `UNAVAILABLE`
- requested interval begins **at or after** `journalStartTimestamp` **and** a valid revision is
  effective at or before the start → `AVAILABLE`
- no revision effective at the start even though the interval is later → `UNAVAILABLE`

`FIXED_INCEPTION_HOLD` is unaffected and remains available wherever it is today.

## 12. Provenance model

```kotlin
enum class ConfigurationProvenance {
    RECORDED_CONFIGURATION,                  // authoritative, from the journal
    OPERATOR_DECLARED_CONFIGURATION,         // forensic override, human-supplied
    INFERRED_BEHAVIORAL_CONFIGURATION,       // diagnostic only, never authoritative
}
```

For authoritative production results, expected provenance is `RECORDED_CONFIGURATION`. The UI/API must
make authoritative vs inferred unmistakable.

## 13. Cache identity

`RebalancerComparisonCacheRepositoryImpl` already keys on an `input_fingerprint`. It must additionally
cover:

- benchmark method
- **fingerprint of the journal revisions in scope** (deterministic digest over the ordered
  `(effective_at, id, symbol, targetWeight)` rows) — *not* just the latest revision timestamp,
  because editing an earlier revision changes the result
- baseline configuration identity
- owner-flow identity
- price-data identity
- comparison scope
- implementation/cache schema version (bump when semantics change)

The `ACCEPTANCE_DB_PATH` + SHA-256 task inputs from the previous pass stay.

## 14. Behavioral-inference code: keep / delete / demote

**DELETE from the production path.** Specifically: `ConfigurationRegimeInference`,
`AnchoredRegimeTransition`, `additionFundingShares`, `ClusterFundingEvidence`, `observedInScopeValues`,
`applyTransitionToAllocation`, `settledAnchorAtOrAfter`, `attachConfigurationAllocations`, the
`anchorRegimes`/`anchorSuppliedRegimes`/`buildInferredRegimes` branch, the
`calculateWithForensicRegimes` seam, and `INFERRED_CONFIGURATION_MATCHED_HOLD` itself.

**The honest case for deletion, not demotion:** on real history this machinery produces a benchmark
that is either wrong (−$904.39) or vacuous (a cash hold). It found no membership change on a
weight-driven strategy. Keeping it "as forensic tooling" means keeping ~600 lines and five test
classes to support a diagnostic nobody has asked for. **Be willing to delete code** — this is the
case. If the inference is wanted later it can be re-derived from the journal's own history.

## 15. `FIXED_INCEPTION_HOLD` status

**Keep.** Its question is: *what would the inception holdings/cash state be worth if simply held under
the existing neutral-flow rules?* It is a forensic/reference benchmark. It is **not** "did active
rebalancing beat holding the intended configured portfolio?" API docs, KDoc and UI copy must say so
explicitly.

## 16. UI / API changes

Rename the exposed method `INFERRED_CONFIGURATION_MATCHED_HOLD` → `CONFIGURATION_MATCHED_HOLD`, and
extend the response with `configurationProvenance`, `journalStartTimestamp`,
`configurationRevisionCount`. UI copy, factual and short:

> "Configuration-matched history is available from **{date}**, when configuration journaling began.
> Earlier history is unavailable because target allocations were not recorded."

Do **not** claim older history was reconstructed. Do not show a chart for an unavailable method.

## 17. Tests added / changed this pass

**No new tests.** Removed 5 stale economics assertions from `ConfigurationMatchedHoldAcceptanceTest`
and replaced them with a comment recording why the economics are deliberately unpinned. The 18 journal
tests and 5 availability tests specified in §15/§16 are **designed, not written** — they belong to the
journal PR, and writing them against a schema that does not exist yet would be writing tests for
fiction. I am flagging this rather than claiming it.

## 18. Stale economics pins removed/replaced

| Pin | Action |
|---|---|
| `finalActual == 21188.49` | **Removed** — Actual NAV is not a benchmark-semantics invariant and moves with the DB. |
| `finalBenchmark == 25366.83` | **Removed** — certified a cash-hold definition now known to be wrong. |
| `finalDifference == -4178.34` | **Removed** — same. |
| `finalDifferencePercent == -16.4717` | **Removed** — same. |
| `points == 300` | **Removed** — an artefact of the resample window, not economics. |

Replacement, when the journal lands: pin **availability, provenance, `journalStartTimestamp`, and
revision identity** — the facts that must be true for the method to mean anything — and pin
economics only for a post-journal interval where the definition is authoritative.

## 19. Data-reconstruction issue draft (not created)

> **Title:** Ledger/trade/snapshot state reconstruction is internally inconsistent, blocking exact historical counterfactual replay
>
> **Severity:** High (blocks all historical counterfactual work; independent of PR #367)
>
> **Observed symptoms**
> 1. Per-asset authoritative ledger balance checkpoints cannot be assembled into a coherent portfolio. At the *same instant* the derived NAV alternates between real values and `$0.01`:
>    ```
>    2025-12-16T19:20Z  NAV $8,316.30  100% USD
>    2025-12-16T19:20Z  NAV $0.01      100% USD
>    2025-12-16T19:45Z  NAV $8,316.30  100% USD
>    2025-12-16T19:45Z  NAV $0.01      100% USD
>    2025-12-17 → 12-19 NAV $8,316.30 constant to the cent for three days
>    ```
> 2. Trade replay does not reconcile with `asset_snapshots`. Replaying all successful non-dry fills from the inception snapshot overshoots the recorded terminal NAV by **$1,886 (10.3%)** and drives cash to **−$6,071**.
> 3. Specific missing legs: BTC's balance change (−0.006915) reconciles only with the `XXBTZUSD` leg (−0.0069); the `XBTUSD` leg (+0.0766) is absent from balance evolution. XRP behaves identically — `XXRPZUSD` (+11.9021) reconciles, `XRPUSD` (+1,966.98) does not.
> 4. LINK and PAXG reconcile **exactly** with volume-only movement (residual 0.00000000), proving fees are quote-side — so the convention is known, and the failures above are real data defects rather than convention confusion.
>
> **Why this blocks historical counterfactual replay**
> A zero-fee Actual counterfactual, a fee-fair synthetic benchmark, and any bootstrap sensitivity
> study all require a replay that ties to the recorded Actual. With a 10.3% unreconciled gap, no
> fee or gross-effect figure derived from this history is trustworthy, and none should be published.
>
> **Proposed investigation areas**
> - Whether the retained `trades` rows are a complete superset of executed fills, or a partial/legacy import (check `source`, `has_valid_*`, `cycle_id` coverage, and `order_intents` reconciliation).
> - Whether ledger `balance` checkpoints are per-asset-after-entry (interpolatable) or per-asset-account-wide, and which the reconstruction assumes.
> - Whether `SnapshotHistoryCalculator` seeding and the trade application path disagree on fee currency or on cash settlement.
> - Whether the `$0.01` states correspond to dust sweeps, card funding normalisation, or partial-universe legacy observation windows.
>
> **Acceptance criteria**
> 1. Replaying all successful non-dry fills from the inception snapshot reproduces the recorded terminal `asset_snapshots` balances per symbol to within the persisted 8-dp unit scale, with residual cash explained line by line.
> 2. No derived portfolio state at any retained instant is incoherent (e.g. NAV oscillating between a real value and $0.01 at the same timestamp).
> 3. A zero-fee Actual counterfactual reconciles to the recorded real-fee Actual within a documented tolerance, with the tolerance justified rather than chosen to fit.

## 20. Recommended PR split

**Split. Do not keep the current PR shape** — churn is not a good enough reason to ship a
behavioural-inference benchmark as the headline artefact.

| PR | Contents | Reviewability | Risk |
|---|---|---|---|
| **PR A** — configuration journal | Tables, migration 16, Exposed tables, repository, service, `ConfigServiceImpl.updateConfig` integration, 18 journal tests, cache fingerprint. Nothing benchmark-related. | Small, one concern, independently testable. Creates no new benchmark. | Migration risk, but additive-only and easy to review in isolation. |
| **PR B** — configuration-matched hold benchmark | `CONFIGURATION_MATCHED_HOLD` consuming the journal, availability semantics, provenance, 5 availability tests, `FIXED_INCEPTION_HOLD` doc clarification, UI/API rename. | Depends on A, but the benchmark logic is then straightforward and reviewable. | Low once A is merged. |
| **PR C** — data-reconstruction defect | The issue body in §19, then whatever the investigation finds. | Independent. | High and open-ended; must not block A or B. |

**PR A and B can stack** (B on A). **C must not** — it is a separate workstream and an open-ended
investigation. The current PR #367 shape (behavioural inference + benchmark + sensitivity claims)
should be **closed and replaced** by A → B. The one durable artefact from the current shape is the
**build-level acceptance-cache fix**, which should move to **PR A** — it is orthogonal, independently
valuable, and should not be lost.

Stacking caution: B on A means B's review requires A's migration to be understood. If review
bandwidth is tight, land A, then B separately, and do not stack more than two deep.

## 21. Adversarial-review findings

Run against my own proposal (six tracks). No new code was written this pass, so these are design
reviews of the plan above.

- **A — provenance.** The design has exactly one authoritative source (the journal). The risk is
  *regression*: a future contributor wiring `observedInScopeValueWeights`-style snapshot reading back
  in. Mitigation: delete the helper entirely rather than leaving it available, and add a test that the
  benchmark's target vector equals the recorded revision's weights.
- **B — chronology.** `epochWeightsAt` becomes "latest revision with `effectiveAt <= t`", which is
  strictly safer than the current anchor-based lookup because revision effective times are recorded
  at change time, not discovered from a later observation. **However**: `updateConfig` stamps
  `effectiveAt` at write time, so a change made *after* a comparison window is correctly invisible to
  that window. Verify the migration seed uses journal-start time, not `now()`, or the first revision
  would be back-dated and silently alter pre-seed comparisons.
- **C — journal durability.** Single write path makes this tractable. Two real gaps: (i) a crash
  between the config file write and the journal insert leaves config and journal divergent — the
  write order must be journal-then-publish, with the config file write as the commit point;
  (ii) `watchConfigChanges` file-watch reloads bypass `updateConfig` and therefore would not journal.
  **This is a genuine bypass path that needs a decision** — either watch-triggered changes journal too,
  or they are documented as out-of-band and rejected. I recommend journaling them, since a file edit is
  a legitimate configuration change.
- **D — benchmark independence.** Holds by construction. Same-membership ratio changes work because
  targets come from revisions, not from membership diffs. Contributions use recorded config.
- **E — legacy cleanup.** Done this pass: stale economics pins removed. Remaining obsolete machinery
  is inventoried in §2 and scheduled for deletion in the journal PR. The PR body still carries
  −$904.39 / −4.0936% / $22,092.88 / $1,861.57 / 0.3746% / $32,949.90 / $17,699.02 / $172.72 and the
  A0–A4 sensitivity table; **all must be deleted**, and the four-transition narrative does not survive
  the corrected code (there is one inferred transition, and it is unapplied).
- **F — cache.** Journal-revision fingerprint is the missing identity input. The `ACCEPTANCE_DB_PATH`
  fix already prevents cross-DB reuse; adding the revision fingerprint to the *comparison* cache
  fingerprint is what prevents stale economics when configuration changes.

## 22. Validation

```
./gradlew :backend:test jacocoTestCoverageVerification spotlessCheck   BUILD SUCCESSFUL
git --no-pager diff --check                                           clean
```

| Gate | Result |
|---|---|
| Backend tests | **3,257 · 0 failures · 0 errors** |
| Branch coverage | **0.9005** (≥ 0.90) |
| Line coverage | **0.9671** (≥ 0.95) |
| Method coverage | **0.9545** (≥ 0.95) |
| Instruction coverage | **0.9660** (≥ 0.95) |
| Spotless | clean |
| `git diff --check` | clean |
| Frontend tests | untouched, not in scope |
| DB-backed acceptance | **not run this pass** — no DB-backed result accepted from cache; the previous pass's run is unaffected by this change (acceptance test assertions removed, not added) |
| Source DB | SHA-256 and `integrity_check ok` re-verified, untouched |

## 23. `git diff --stat`

```
 backend/build.gradle.kts                           |  35 +
 .../service/impl/history/BenchmarkEvent.kt         |  57 +-
 .../impl/history/ConfigurationRegimeInference.kt    |   3 +
 .../impl/history/RebalancerComparisonCalculator.kt  | 586 ++++++++++--
 .../ConfigurationInferenceSensitivityTest.kt        |   1 +
 .../impl/history/ConfigurationMatchedHoldAcceptanceTest.kt |   7 +
 .../impl/history/ConfigurationResetReplayTest.kt    | 853 +++++-----
 .../ForensicComparisonCacheIsolationTest.kt         |   1 +
 .../service/impl/history/ForensicRegimeSeamTest.kt  |   1 +
 9 files changed, 930 insertions(+), 614 deletions(-)
```

## 24. `git status`

```
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

HEAD unchanged at `35f19587`. Nothing staged, nothing committed. A stray `BOOTSTRAP-INVESTIGATION.md`
that had landed in the repo root was removed.

## 25. Recommendation

### **NEEDS_FURTHER_WORK**

**The architecture I recommend is settled and I would defend it. The implementation is not started,
deliberately.** Specifically:

- **This pass is complete and committable as-is** if you want it: the build-cache fix plus the
  membership-scoped reset correctness work, with stale economics pins removed. It is a strict
  improvement on `35f19587` and nothing in it claims a performance conclusion.
- **But it is not the PR you should merge.** Its benchmark is provably vacuous on real history, and
  the sensitivity study showed any baseline is worth ±$15.8k with a sign flip. Land it as a
  correctness fix if you want it, then close PR #367 and open **PR A** (journal) and **PR B**
  (benchmark) per §20.
- **Open PR C** from the §19 issue body when you're ready to authorise it. It is independent and must
  not gate A or B.
- **Two decisions I need from you before the journal PR:** (1) does a `watchConfigChanges` file-watch
  configuration edit count as a journal-worthy change, or is it out-of-band? (2) should
  `FIXED_INCEPTION_HOLD` be re-labelled in the UI to make clear it answers the cash question, or left
  as-is to avoid churn?

The one thing I would not do is keep the current PR shape. It presents behavioural inference as an
authoritative benchmark, and we now have direct evidence that it cannot be.
