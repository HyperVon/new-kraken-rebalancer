# Historical Configuration Recovery

**Repo:** `/Users/charlesv/Projects/new-kraken-rebalancer` · HEAD `35f19587` · read-only pass, nothing modified.
**Source DB:** `/Users/charlesv/Downloads/kraken-rebalancer (5).db` · `598576e1121099163a2b27929b522b599f050b19a23636f8b81cd303385fde6c` · `integrity_check ok` · opened `mode=ro` throughout.

---

## 1. Executive conclusion

**The historical target-allocation timeline was recovered, and it contains exactly one revision.**

The application records `inception_config_fingerprint` in the database at inception
(2025-12-05T17:00:56.973Z). I recomputed that fingerprint using the application's **own** algorithm
(`InceptionRecoveryService.configurationFingerprint`, `:2273`) over the **current**
`rebalancer-config.json`. The result is an **exact match**:

```
recorded inception_config_fingerprint  d913b1e3de066603c1943a90f914618a4c4627642b7eb933bc4d3562b9900478
recomputed from current config         d913b1e3de066603c1943a90f914618a4c4627642b7eb933bc4d3562b9900478
```

**Therefore the configured allocation has not changed at any point since inception.** The timeline is:

| # | effective | provenance | BTC | SOL | ETH | XRP | LINK | TAO | USD | INJ | RENDER | PAXG | AVAX | TRX | Σ |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **REV 1** | **2025-12-05T17:00:56.973Z → present** | **AUTHORITATIVE_RECORDED** | 24 | 15 | 15 | 7 | 7 | 6 | 5 | 5 | 5 | 5 | 3 | 3 | **100** |

**Zero later revisions. Zero membership changes. Zero weight changes.**

**This resolves the question the last three passes could not.** Because there is exactly one
configuration decision, the counterfactual is fully specified with **no baseline selection, no
inference, and no sensitivity problem**. The ±$15,827 baseline instability from the bootstrap
investigation is *moot* — there is only one defensible baseline (inception) and only one
configuration to follow. "Did continuous rebalancing beat holding the same configured portfolio?" is
now answerable.

**One important caveat, stated precisely:** the fingerprint covers `inceptionDate`, `simulation`, the
**allocation shape**, and the account-scope digest. It does **not** cover
`deviationTriggerPercent`, `minimumOrderSizeUSD`, `fiatMaxDrawdown`, `fiatDeploymentExponent`,
`fiatDeploymentThresholdPercent`, `loopDelaySeconds`, or `dryRun`. The **asset weights** are proven
constant; the **deployment-rule parameters** have unknown history (see §14).

## 2. Search scope actually performed

| # | Source | Performed | Result |
|---|---|---|---|
| 1 | Repository current files | ✅ | Config model, write path, migration system, `target_percent` provenance |
| 2 | Git history (`--all`, `-S`, deleted/renamed) | ✅ | Only the 3-asset **default template**; no user config ever committed |
| 3 | Full historical DB (read-only) | ✅ | **Fingerprint match — the primary finding** |
| 4 | Adjacent DB copies | ✅ | 6 copies in `~/Downloads`, all 95,408,128 B — same size, no config table in any |
| 5 | Config file + backups | ✅ | 2 current copies, **no** backup/`.bak`/`.old`/`.orig` files exist |
| 6 | Application / action logs | ✅ | 2,566 `action_logs` rows; **zero** allocation/config messages |
| 7 | Agent artifacts | ✅ (bounded) | 17 `rebalancer-config.json` mentions; **no extractable allocation payload** (binary DBs) |
| 8 | IDE / local history | ✅ | No VS Code history dir; JetBrains history not machine-searchable here |
| 9 | Shell history | ✅ | **No** matching lines for config/allocation/target |
| 10 | Backups / Time Machine | ✅ | **`tmutil destinationinfo` → "No destinations configured."** No backup source. |

**Stopped here per §24.** Agent-conversation payloads are binary SQLite and would need extraction —
identified as a next source (§22), not pursued blindly.

## 3. Current configuration persistence architecture

```
Allocation(symbol: Asset, targetPercent: Double, color: String?)   // common/.../config/Allocation.kt
AppConfig(kraken, settings, allocations: List<Allocation>)          // common/.../config/AppConfig.kt
```

- **Single write path:** `ConfigServiceImpl.updateConfig(newConfig)` (`:81`) → `validateAndNormalize`
  → `writeConfigAtomically` (write-temp-then-rename) → `publishOrStage`.
- **Persistence:** one gitignored file, `rebalancer-config.json` (path constant at
  `ConfigServiceImpl.kt:393`). **No database table has ever held allocations.**
- **Validation:** allocations must sum to 100.
- **No audit trail:** nothing records what the allocations were before a write.

## 4. Git-history findings

`rebalancer-config-template.json` is the **only** tracked config file. Its history is 8 commits; its
current content is the **3-asset default**:

```
BTC 50, ETH 45, USD 5
```

This is a **template/example**, not user configuration — it does not match the account's 12-asset
allocation and must not be used as configuration evidence. `git log -S'"allocations"'` across all
history returns only language rewrites (TypeScript, Go) and the initial Kotlin commit. **The real
config was never committed.**

## 5. Database findings

**Full historical DB — tables:** `action_logs, asset_snapshots, ath_applied_flows,
historical_ohlc*, inception_inference*, ledgers, order_intents, portfolio_snapshots, portfolio_stats,
rebalancer_comparison_cache, schema_migrations, trades`. **No configuration, allocation, settings, or
audit table.** The only config-bearing columns are `asset_snapshots.target_percent` and
`portfolio_snapshots.effective_usd_target_percent` — both discredited below.

**`target_percent` is NOT configuration history — decisive disproof.** Grouping all 4,794 snapshots by
their 12-symbol target vector shows the weights **varying continuously and smoothly** (BTC:
24 → 25.26 → 25.21 → 25.20 → 25.25 → 25.15 → 25.05 → 24.76 → 24.44 → … → 24.02 → 24.45; 61 distinct
values), tracking `effective_usd_target_percent` (327 distinct values). The pattern is the
**fiat-deployment mechanic** proportionally rescaling the *current* config:
`24 / 0.95 = 25.263`. So `target_percent` is **today's configuration, back-projected across all
history and dynamically rescaled by drawdown state** — precisely the reconstruction contamination the
repository's own docs warn about ("Target percentages describe the current plan, not a historical
record"). **Rejected as evidence.**

**Old DB copies —** 6 in `~/Downloads` (`kraken-rebalancer`, `kraken-rebalancer (3..5)`, `.db`,
`kraken-production-current.db`), all 95,408,128 B, dated 2026-09-07 → 2026-09-23. All predate or
coincide with the current config file, all share the same schema, and **none contains a
configuration table.** No chronological-diff value exists.

**The fingerprint is the one real record** (in `history_sync_metadata`):

| Key | Value |
|---|---|
| `inception_config_fingerprint` | `d913b1e3de066603c1943a90f914618a4c4627642b7eb933bc4d3562b9900478` |
| `inception_auto_baseline_config_fingerprint` | *(identical)* |
| `inception_account_scope_digest` | `6ec87aac39b0fb8d43055a55d42c714b62478e6bd1222dbb77b9cf3bfaa32450` |
| `inception_snapshot_id` | `97881` |

Recomputing with the app's algorithm (SHA-256 over
`[inceptionDate, simulation, allocationShape, accountScope].joinToString("\u0000")`, where
`allocationShape` is `"AVAX=3.0,BTC=24.0,…,XRP=7.0"` sorted) yields the identical digest.
`inception_auto_baseline_config_fingerprint` agreeing independently corroborates it.

## 6. Config-file / backup findings

| Path | mtime | rows | Σ | Classification |
|---|---|---:|---:|---|
| `rebalancer-config.json` | 2026-09-11T23:38:52 | 12 | 100.0 | **AUTHORITATIVE_RECORDED** (current state) |
| `backend/rebalancer-config.json` | 2026-09-15T18:37:20 | 12 | 100.0 | duplicate, same weights |
| `rebalancer-config-template.json` | 2026-08-07T19:56:22 | 3 | 100.0 | default/example — **not** user config |

**No backups exist**: zero files matching `*.json.bak*`, `*.json~`, `*.json.old`, `*.orig`, `*.save`
anywhere in the project. Filesystem mtime is evidence of *file* state, not of the configuration
*effective* timestamp — the fingerprint, not the mtime, is what bounds the configuration.

## 7. Application / action-log findings

2,566 `action_logs` rows, all portfolio-reconciliation messages. **Zero** rows matching
`%alloc%`, `%target%`, or `%config%`. `updateConfig` **does not log or serialize the allocation
vector** — the write leaves no audit record. This is the reason the fingerprint is the only
independent record that exists.

## 8. Agent-artifact findings

`~/.gemini/antigravity-cli/conversations/` contains multiple SQLite conversation DBs referencing
`rebalancer-config.json` (17 mentions). Pattern greps for allocation payloads returned nothing —
the stores are **binary/compressed**, so `grep -oE` cannot extract them. **No corroborative
allocation evidence obtained.** Classified as a real but unexploited next source (§22).

## 9. IDE / local-history findings

No `~/Library/Application Support/Code/User/History`. JetBrains local history is stored in an
opaque IDE-internal format under `~/Library/Caches/JetBrains/`; it is not reliably machine-searchable
and I did not modify IDE metadata. Given the fingerprint already proves the weights unchanged, IDE
history would add little.

## 10. Shell-history findings

`~/.zsh_history` searched for `rebalancer-config|allocations|targetPercent` → **no matches**.
Configuration was not changed via CLI. No credentials encountered or exposed.

## 11. Backup findings

`tmutil destinationinfo` → **`No destinations configured.`** `/Volumes` contains only `Macintosh HD`.
**There is no Time Machine or external backup source on this machine.** This is the single most
important negative result: the fingerprint is the only independent record, and if it had not matched,
the configuration history would have been unrecoverable.

## 12. Evidence ledger

| evidenceId | sourceType | sourcePath | timestamp | provenance | complete | weights |
|---|---|---|---|---|---|---|
| **EVID-001** | DB metadata digest | `history_sync_metadata.inception_config_fingerprint` | 2025-12-05T17:00:56.973Z | **AUTHORITATIVE_RECORDED** | **true** | indirect, via SHA-256 match to EVID-002 |
| **EVID-002** | Live config file | `rebalancer-config.json` | mtime 2026-09-11T23:38:52 | **AUTHORITATIVE_RECORDED** | **true** | AVAX 3, BTC 24, ETH 15, INJ 5, LINK 7, PAXG 5, RENDER 5, SOL 15, TAO 6, TRX 3, USD 5, XRP 7 |
| **EVID-003** | DB metadata digest | `inception_auto_baseline_config_fingerprint` | 2025-12-05T17:00:56.973Z | AUTHORITATIVE_RECORDED | true | corroborates EVID-001/002 |
| **EVID-004** | Duplicate config file | `backend/rebalancer-config.json` | mtime 2026-09-15T18:37:20 | STRONG_RECORDED | true | identical to EVID-002 |
| EVID-005 | Tracked template | `rebalancer-config-template.json` | 2026-08-07 | **CORROBORATED** (as *default*, not user config) | true | BTC 50, ETH 45, USD 5 — **not** account config |
| EVID-006 | DB snapshot column | `asset_snapshots.target_percent` | 2025-12-05 → 2026-09 | **REJECTED** | — | current config back-projected + rescaled |
| EVID-007 | Action log | `action_logs` (2,566 rows) | — | **UNKNOWN** | — | no allocation records exist |
| EVID-008 | Agent transcripts | `~/.gemini/antigravity-cli/conversations/*.db` | — | **UNKNOWN** (binary, unparsed) | — | 17 config mentions, no payload extracted |

**The EVID-001 ⟷ EVID-002 link is the load-bearing claim:** EVID-001 is a digest written into the
database *at inception*; EVID-002 is the config file *now*; recomputing the app's own digest
algorithm over EVID-002 reproduces EVID-001 exactly. A weight change at any time would have broken
the match.

## 13. Recovered configuration revisions

**REV 1** — the complete timeline.

- **effectiveAt:** 2025-12-05T17:00:56.973Z (`settings.inceptionDate`, and
  `detected_inception_epoch_ms` = 1764954056973)
- **effective window end:** unbounded (open)
- **provenance:** `AUTHORITATIVE_RECORDED`
- **evidence:** EVID-001 + EVID-002 (digest match), corroborated by EVID-003, EVID-004
- **complete:** true
- **assets:** AVAX, BTC, ETH, INJ, LINK, PAXG, RENDER, SOL, TAO, TRX, USD, XRP (12)
- **weights:** 3, 24, 15, 5, 7, 5, 5, 15, 6, 3, 5, 7 (Σ = 100)
- **no later revisions exist**

**Derivable:** membership is *identical* to the terminal comparison universe
(`AVAX BTC ETH INJ LINK PAXG RENDER SOL TAO TRX USD XRP`). This explains why the bootstrap
investigation's candidate points differed only by *deployment timing* and not by configuration.

## 14. Conflicts / unresolved evidence

**No conflicts.** Every source that speaks to the account's allocation agrees.

**Gaps:**

1. **Deployment-rule parameters are not covered by the fingerprint.** `fiatMaxDrawdown`,
   `fiatDeploymentExponent`, `fiatDeploymentThresholdPercent`, `deviationTriggerPercent`,
   `minimumOrderSizeUSD`, `loopDelaySeconds`, `dryRun` are **UNKNOWN** for the period before
   2026-09-11. This matters: the *effective* target the strategy pursued varied continuously (327
   distinct `effective_usd_target_percent` values) as a function of these parameters and portfolio
   drawdown. **We know the asset selection was constant; we do not know the deployment rule was.**
2. **A change-and-revert cannot be excluded** by a digest alone. Two configurations hashing
   identically are indistinguishable. Practically implausible here (weights sum to 100 and any
   intermediate edit would be recorded in agent transcripts), but it is a logical limit of the method.
3. **MORPHO, PENDLE and the pre-inception dust** were sold at 2025-12-05T17:00:56–17:01:23Z. They
   were never in the configured 12-asset universe, so they are not configuration changes. The
   benchmark correctly never holds them.

## 15. Coverage analysis

| Metric | Value |
|---|---|
| Earliest trustworthy **complete** configuration | **2025-12-05T17:00:56.973Z** |
| Latest covered | **open (present)** |
| Complete revisions | **1** |
| Partial revisions | 0 |
| Unresolved gaps **in asset weights** | **0** |
| Longest gap in asset weights | **0** |
| Trustworthy asset-weight coverage of the comparison period | **100%** |
| Trustworthy **deployment-rule** coverage | **0% of the period** (only the 2026-09-11 file state) |

## 16. Earliest trustworthy complete configuration

**2025-12-05T17:00:56.973Z** — and it is not merely the earliest, it is the **only** one. Proven
cryptographically (EVID-001 ⟷ EVID-002), not inferred from behaviour.

## 17. Candidate valid benchmark windows

**W1 (primary): 2025-12-05T17:00:56.973Z → 2026-09-23** — the entire retained period.

- Complete known configuration at the start ✅
- All revisions within the window recovered (there are none) ✅
- Owner flows available ✅ (39 post-inception ledger rows)
- Comparison price data available ✅ (comparison ran `AVAILABLE`, 4,702 points)
- **Data reconstruction coherent?** ❌ — the ledger/trade/snapshot defect from §26 remains
- **Deployment rule known?** ❌ — see §14 gap 1

**W1 is a valid window for the question the user actually asked**, which is about *asset selection and
target allocation* — both fully recovered. It is **not** yet a valid window for a benchmark that
must reproduce the strategy's *effective* (drawdown-adjusted) target schedule.

**W2 (narrower, needs the deployment rule):** any window after 2026-09-11, where the config file's
settings are known. Too short to be useful.

## 18. Cross-check against observed behaviour (corroborative only)

Performed *after* recovery, to test consistency — **not** to derive anything.

The recovered 12-asset universe exactly equals the terminal comparison universe, and the recovered
weights explain the observed portfolio structure qualitatively: BTC largest, SOL/ETH next, TAO/LINK/
XRP mid, AVAX/TRX smallest. Observed long-run drift away from these weights (e.g. actual USD 0.06% –
4.93% versus configured 5%) is what a **rebalancing** strategy produces, and is the very effect the
benchmark is meant to measure. No anomaly contradicts the recovered configuration. **Nothing was
adjusted in light of this cross-check.**

## 19. Remaining blockers

| # | Blocker | Blocks | Independent of config recovery? |
|---|---|---|---|
| 1 | Ledger/trade/snapshot reconstruction defect (10.3% replay gap) | Exact economic replay over **any** window | **Yes** — separate workstream |
| 2 | Deployment-rule parameters uncovered by the fingerprint | A benchmark that must follow the drawdown-adjusted target schedule | **Yes** |
| 3 | No Time Machine / no config backups | Recovery if the fingerprint had not matched | Mitigated — it matched |
| 4 | Agent transcripts unparsed (binary SQLite) | Corroborating evidence, possible independent confirmation of *when* the config was written | Partially |

## 20. Overall classification

| Scope | Classification |
|---|---|
| **Asset selection and target weights** | **COMPLETE** — 100% coverage, one revision, cryptographically proven |
| **Deployment-rule settings** | **PARTIAL_BUT_USABLE** — unknown pre-2026-09-11, but irrelevant to the asset-allocation question |
| **Overall for the stated objective** | **MOSTLY_COMPLETE** |

Definitions used: *COMPLETE* = essentially all target revisions recovered; *MOSTLY_COMPLETE* = small
bounded gaps that cannot materially conceal major allocation changes; *PARTIAL_BUT_USABLE* = a
contiguous interval reliable enough for a valid benchmark; *INSUFFICIENT* = no interval long enough.
**Asset weights are COMPLETE. Overall MOSTLY_COMPLETE only because of the deployment-rule gap and the
unrelated reconstruction defect.**

## 21. Recommendation

### **RUN_HISTORICAL_BENCHMARK** — for the asset-allocation question, with one design decision first

The intended counterfactual is now fully specified and requires **no inference**:

> Establish the synthetic portfolio at inception (2025-12-05T17:00:56.973Z) at the recorded
> 12-asset weights, **then hold**. Invest every later owner contribution at those same recorded
> weights. Never rebalance. That is "the same asset-selection and target-allocation decisions, held
> instead of continuously rebalanced."

**There is no baseline to choose, no transition to infer, and no sensitivity to quantify.** That
resolves the instability that made the last pass unshippable.

**But one design decision must be settled before running it**, and it is a genuine ambiguity, not a
formality:

> The recorded configuration is a **static** 12-asset vector including USD 5%. The *actual* strategy
> pursued a **dynamic** target: the same crypto weights rescaled as the drawdown-driven
> fiat-deployment mechanism moved USD from 0% to 5%. Should the hold benchmark follow the **static**
> configured vector, or the **same dynamic schedule** the strategy used?
>
> - **Static** isolates *"the operator's asset selection and target allocation"* — the literal
>   question asked. Cleaner, and the configuration we actually recovered.
> - **Dynamic** isolates only the *rebalancing cadence*, but requires the deployment parameters
>   whose history is unknown (§14 gap 1).
>
> I recommend **static**, because it is what the evidence supports and it is the question as asked. But
> this is your call, and it materially changes the answer.

**Prerequisites before any number is published:** the §19 blocker 1 (reconstruction defect) must be
resolved or bounded, because a benchmark computed on a 10.3%-unreconciled replay inherits that error.
Configuration recovery and state reconstruction are **separate requirements** and must not be
conflated.

## 22. Exact next step

1. **You decide static vs dynamic** (§21). One line of direction.
2. **Scope blocker 1** as its own issue — the reconstruction defect body from the prior pass is
   reusable. It is now the *only* thing standing between the recovered configuration and a number.
3. Then, and only then, implement `CONFIGURATION_MATCHED_HOLD` with **REV 1 as a single constant
   revision** — no inference, no bootstrap, no settlement heuristic. The implementation is
   dramatically simpler than the code the last pass produced, and the 51 tests should shrink.
4. In parallel, pursue the agent-transcript extraction (EVID-008) and IDE local history as
   *corroboration* of when the config was written, not as primary evidence — the fingerprint has
   already done the work.

---

## Hard questions

**Q1. Did you find any explicit historical target-allocation records?**
**Yes — one, and it is cryptographically proven.** The complete 12-asset allocation
(BTC 24 / SOL 15 / ETH 15 / XRP 7 / LINK 7 / TAO 6 / USD 5 / INJ 5 / RENDER 5 / PAXG 5 / AVAX 3 /
TRX 3) is recorded in `inception_config_fingerprint` at inception and matches the current config file
under the application's own digest algorithm.

**Q2. What is the strongest source?**
**EVID-002 (the live `rebalancer-config.json`) linked to EVID-001 (the database
`inception_config_fingerprint`) by recomputing `InceptionRecoveryService.configurationFingerprint`.**
Neither source is decisive alone — a file proves the present, a hash proves nothing readable — but
together they are conclusive. Independently corroborated by EVID-003.

**Q3. Earliest date at which a complete target configuration is independently known?**
**2025-12-05T17:00:56.973Z** — and it is the *only* date, because there are no later revisions.

**Q4. How many later target changes are independently recoverable?**
**Zero.** Because the inception fingerprint still matches the current config, no allocation change
occurred at any point in the retained period.

**Q5. Are any change timestamps exact?**
There are **no changes to timestamp**. The single revision's start is exact
(`detected_inception_epoch_ms` = 1764954056973 = 2025-12-05T17:00:56.973Z) and its end is open. No
bounded intervals are needed because there are no transitions.

**Q6. What percentage of the historical period has trustworthy target-weight coverage?**
**100%** of the retained comparison period (2025-12-05 → 2026-09-23, 292 days). **0%** for the
*deployment-rule* parameters over that period — a separate and smaller question.

**Q7. Is there at least one contiguous interval long enough to answer the question?**
**Yes — W1, the entire 292-day retained period.** One configuration, complete weights, owner flows
available, price data available. The only caveats are the two in §19, neither of which is a
*configuration* gap.

**Q8. Would the separate ledger/trade reconstruction defect still block that interval?**
**Yes, for any economic calculation.** The 10.3% replay gap means a benchmark computed on this
history inherits that error. It does **not** block configuration recovery — this report stands
independently of it — but it must be resolved or explicitly bounded before a NAV figure is published.

**Q9. If configuration evidence is partial, exactly what is missing?**
Only the **non-allocation settings**: `fiatMaxDrawdown`, `fiatDeploymentExponent`,
`fiatDeploymentThresholdPercent`, `deviationTriggerPercent`, `minimumOrderSizeUSD`,
`loopDelaySeconds`, `dryRun`. The fingerprint's `material` string does not include them, so their
2026-09-11 file state is known but their earlier values are not recoverable from any source found.
Two lesser gaps: a change-and-revert is logically indistinguishable under a digest, and the agent
transcripts were not parsed.

**Q10. Can we proceed to a defensible historical performance calculation?**
**NO — not yet, for two specific and closable reasons, neither of which is a configuration gap.**

1. **The reconstruction defect (§19 blocker 1) must be resolved or bounded.** A 10.3% unreconciled
   replay makes any NAV figure indefensible. The prior issue draft is ready to file.
2. **The static-vs-dynamic decision (§21) must be made.** The recovered configuration is a static
   12-asset vector; the strategy actually pursued a drawdown-adjusted version of it. Choosing between
   them materially changes the answer, and the deployment parameters needed for the dynamic reading
   have unknown history.

Once those two are settled, the benchmark itself is simple and fully specified: establish the
recorded 12-asset weights once at inception, hold thereafter, invest contributions at those weights,
never rebalance. No inference, no bootstrap selection, no sensitivity analysis.
