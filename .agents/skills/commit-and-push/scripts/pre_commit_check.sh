#!/usr/bin/env bash
set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# Kotlin 2.4.20-RC's downloaded Yarn 1.22.22 emits DEP0169 from its own GitResolver
# under modern Node. Suppress only that external warning during quality checks.
case " ${NODE_OPTIONS:-} " in
  *" --disable-warning=DEP0169 "*) ;;
  *) export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--disable-warning=DEP0169" ;;
esac

echo "=== Step 0: Changelog policy (no [Unreleased] section) ==="
if grep -nE '^##[[:space:]]*\[[Uu]nreleased\]' CHANGELOG.md; then
  echo "ERROR: CHANGELOG.md contains an [Unreleased] section." >&2
  echo "Policy: every shippable change set gets a dated SemVer heading immediately" >&2
  echo "        (## [X.Y.Z] - YYYY-MM-DD). See changelog-and-docs-sync skill." >&2
  exit 1
fi

echo "=== Step 1: Running Markdown Linting ==="
markdown_files=()
while IFS= read -r file; do
  markdown_files+=("$file")
done < <(git ls-files -- '*.md' '*.mdc')
npx markdownlint-cli "${markdown_files[@]}"

echo "=== Step 1.25: Validating Agent Skills & Links ==="
python3 .agents/scripts/validate_skills.py

echo "=== Step 1.5: Running Kotlin Code Formatting & Line-Length Check (Spotless / ktlint) ==="
./gradlew spotlessCheck

echo "=== Step 2: Running Gradle Build & JVM Coverage Verification ==="
./gradlew build jacocoTestCoverageVerification

echo "=== Step 3: Running Client-Side Kotlin/JS Tests ==="
./gradlew :frontend-js:jsBrowserTest

echo "=== All pre-commit checks PASSED successfully ==="
