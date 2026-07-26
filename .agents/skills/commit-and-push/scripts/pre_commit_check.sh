#!/usr/bin/env bash
set -e

echo "=== Step 0: Changelog policy (no [Unreleased] section) ==="
if grep -nE '^##[[:space:]]*\[[Uu]nreleased\]' CHANGELOG.md; then
  echo "ERROR: CHANGELOG.md contains an [Unreleased] section." >&2
  echo "Policy: every shippable change set gets a dated SemVer heading immediately" >&2
  echo "        (## [X.Y.Z] - YYYY-MM-DD). See changelog-and-docs-sync skill." >&2
  exit 1
fi

echo "=== Step 1: Running Markdown Linting ==="
markdown_files=(.agents/AGENTS.md CHANGELOG.md README.md docs/*.md .agents/skills/**/SKILL.md)
[[ -f CONTRIBUTING.md ]] && markdown_files+=(CONTRIBUTING.md)
[[ -f SECURITY.md ]] && markdown_files+=(SECURITY.md)
npx markdownlint-cli "${markdown_files[@]}"

echo "=== Step 1.5: Running Kotlin Code Formatting & Line-Length Check (Spotless / ktlint) ==="
./gradlew spotlessCheck

echo "=== Step 2: Running Gradle Build & JVM Coverage Verification ==="
./gradlew build jacocoTestCoverageVerification

echo "=== Step 3: Running Client-Side Kotlin/JS Tests ==="
./gradlew :frontend-js:jsBrowserTest

echo "=== All pre-commit checks PASSED successfully ==="
