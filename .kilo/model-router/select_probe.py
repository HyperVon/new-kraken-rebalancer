#!/usr/bin/env python3
"""Diagnostic probe for .kilo/model-router selection.

Runs prompts through the automatic model selector and prints the inferred
profile, the selected route, and cheap/strong alternatives. Uses
select_with_tps_guard, so free routes are throughput-probed (real HTTP call,
cached 60 min) and slow ones are re-selected. Used when tuning
DEFAULT_PROFILES / ranking. Reads the live catalog, AA cache, and quota snapshot;
expects to run from .kilo/model-router/ (so `import router` resolves).

Usage:
    python3 select_probe.py                 # run the built-in coverage matrix
    python3 select_probe.py --task "..."    # probe a single arbitrary prompt
"""

import argparse
import json

import availability
import router


def fmt(c):
    return (f"{c.provider}/{c.model} bill={c.billing} q={c.quality} "
            f"cost={c.effective_cost}({c.effective_cost_source}) quota={c.quota_state}/{c.quota_percent} "
            f"var={c.variant} match={c.aa_match}")


def reset(candidates):
    """Clear per-task selection fields so repeated runs are not polluted."""
    for c in candidates:
        c.quality = None
        c.effective_cost = None
        c.quota_state = None
        c.quota_percent = None
        c.rejection = None


def probe_one(task, candidates, config, expected=None):
    reset(candidates)
    name, prof = router.profile_config(config, "auto", task)
    sensitive = router.is_sensitive(task, None)
    router.apply_ranking_data(candidates, prof, config)

    def qualifies(c):
        try:
            import arr_bridge

            ok, _ = arr_bridge._kraken_non_quality_eligible(c, prof, config, sensitive)
            if not ok:
                return False
            # mimic legacy relax_quality=False quality gate for diagnostics
            if c.quality is None:
                return False
            minimum = router.effective_minimum(prof)
            if c.quality < minimum:
                return False
            return True
        except Exception:
            return False

    qualifying = [c for c in candidates if qualifies(c)]
    try:
        selected, warnings = router.select_with_tps_guard(candidates, prof, config, sensitive, [], [])
    except router.RouterError as e:
        print(f"\n=== {task!r}")
        print(f"  expected={expected} got={name}  qualifying={len(qualifying)}  ERROR: {e}")
        return

    q_sorted = sorted(
        qualifying,
        key=lambda c: (c.effective_cost if c.effective_cost is not None else float("inf"),
                       -(c.quality if c.quality is not None else 0.0)),
    )
    n_free = sum(1 for c in qualifying if c.billing == "free")
    print(f"\n=== {task!r}")
    print(f"  expected={expected} got={name} min={prof.get('minimum')} margin={prof.get('margin')} "
          f"metric={prof.get('metric')} sensitive={sensitive}")
    print(f"  qualifying={len(qualifying)} (free={n_free}, non-free={len(qualifying) - n_free})")
    print(f"  SELECTED: {fmt(selected)}")
    tps = router.cached_tps(selected.route, config)
    if tps is not None:
        print(f"  tps={tps:g} tokens/sec")
    for w in warnings:
        print(f"  warn: {w}")
    print("  cheapest 3:")
    for c in q_sorted[:3]:
        print(f"    {fmt(c)}")
    print("  best-quality 3:")
    for c in sorted(qualifying, key=lambda c: -(c.quality if c.quality is not None else 0.0))[:3]:
        print(f"    {fmt(c)}")


COVERAGE = [
    # (expected_profile, prompt)
    ("trivial", "Format this Kotlin file with ktlint"),
    ("trivial", "Look up and list the configured statuses"),
    ("routine", "Fix the typo in the docstring of calculateX"),
    ("routine", "Add a minor comment to the rebalance loop and clean up imports"),
    ("coding", "Fix the failing JVM test in PortfolioCalculations"),
    ("coding", "Implement a new Ktor route for the history export endpoint and add unit tests"),
    ("complex-coding", "Refactor the concurrency handling in the rebalance engine to remove the race condition"),
    ("complex-coding", "Optimize the portfolio math for performance and fix the parallel staleness bug"),
    ("agentic", "Build a reusable workflow that fetches prices, computes deviations, and files a report"),
    ("quick-review", "Review the diff of my last commit for obvious issues"),
    ("quick-review", "Do a quick review of this documentation change"),
    ("detailed-review", "/code-review of this branch's changes vs main"),
    ("detailed-review", "Architecture review of the rebalancer design"),
    ("critical", "Review how API credentials are stored and assess the security of the secret handling"),
    ("critical", "Audit for any place we could lose funds on a partial fill"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", help="probe a single arbitrary prompt")
    args = parser.parse_args()

    config = json.loads(open("config").read())
    raw, _ = router.fetch_catalog(config, refresh=False)
    aa, aa_status = router.load_artificial_analysis(config, refresh=False)
    snap = availability.snapshot(config)
    candidates = router.build_candidates(raw, config, aa, snap)
    print(f"catalog={len(raw)} aa={aa_status} candidates={len(candidates)}")

    if args.task:
        probe_one(args.task, candidates, config)
    else:
        for expected, task in COVERAGE:
            probe_one(task, candidates, config, expected)


if __name__ == "__main__":
    main()
