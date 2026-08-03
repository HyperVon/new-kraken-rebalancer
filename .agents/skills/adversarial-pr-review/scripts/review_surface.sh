#!/usr/bin/env bash
set -euo pipefail

BASE=${1:-main}
BRANCH=$(git branch --show-current)
MERGE_BASE=$(git merge-base "$BASE" HEAD)

printf 'branch: %s\n' "$BRANCH"
printf 'base: %s\n' "$BASE"
printf 'merge-base: %s\n' "$MERGE_BASE"
printf '\nDiff summary:\n'
git diff --stat "$MERGE_BASE"...HEAD

printf '\nChanged paths by top-level directory:\n'
git diff --name-only "$MERGE_BASE"...HEAD | python3 -c '
import sys
from collections import Counter

paths = [line.strip() for line in sys.stdin if line.strip()]
counts = Counter(path.split("/", 1)[0] for path in paths)
for root, count in sorted(counts.items()):
    print(f"{root}: {count}")
print(f"total: {len(paths)}")
'

printf '\nChanged paths:\n'
git diff --name-only "$MERGE_BASE"...HEAD
