#!/usr/bin/env bash
set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# Kotlin 2.4.10's downloaded Yarn 1.22.22 emits DEP0169 from its own GitResolver
# under modern Node while resolving the generated Karma Git dependency. Keep this
# filter scoped to repository quality tooling; application launches stay unchanged.
case " ${NODE_OPTIONS:-} " in
    *" --disable-warning=DEP0169 "*) ;;
    *) export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--disable-warning=DEP0169" ;;
esac

echo "================================================="
echo "   AUTONOMOUS CODE OPTIMIZER — AUDIT & SCAN"
echo "================================================="

ERRORS_FOUND=0

echo ""
echo "--- 1. Scanning for inline FQNs in Kotlin sources ---"
FQN_MATCHES=$(git grep -n -I -E 'com\.gemini\.krakenbot\.|java\.(io|math|time|nio)\.|io\.kotest\.|org\.w3c\.dom\.|org\.jetbrains\.kotlin\.gradle\.' -- '*.kt' '*.kts' 2>/dev/null | grep -vE ':(package|import)[[:space:]]' | grep -vE ':[[:space:]]*(//|\*)' | grep -vE 'Class\.forName|Main-Class|className|mainClass\.set' || true)
if [ -n "$FQN_MATCHES" ]; then
    FQN_COUNT=$(echo "$FQN_MATCHES" | wc -l | tr -d ' ')
    echo "[!] Found $FQN_COUNT inline FQN reference(s) in Kotlin source code:"
    echo "$FQN_MATCHES"
    ERRORS_FOUND=$((ERRORS_FOUND + FQN_COUNT))
else
    echo "[✓] Zero inline FQNs found in Kotlin source files."
fi

echo ""
echo "--- 2. Scanning for hardcoded user paths and machine-specific hostnames ---"
# Generic policy examples and this scanner mention placeholder paths/hostnames by design.
PATH_MATCHES=$(git grep -n -I -E '/Users/[[:alnum:]_.-]+/|/home/[[:alnum:]_.-]+/|macbook|charles-pc' -- . ':(exclude)build/**' ':(exclude).gradle/**' ':(exclude)node_modules/**' | \
    grep -vE '(^|:)(CHANGELOG\.md|\.agents/skills/[^:]+/SKILL\.md|\.agents/skills/[^:]+/scripts/[^:]+|\.agents/skills/autonomous-code-optimizer/scripts/audit_and_verify\.sh):' || true)
if [ -n "$PATH_MATCHES" ]; then
    PATH_COUNT=$(echo "$PATH_MATCHES" | wc -l | tr -d ' ')
    echo "[!] Found $PATH_COUNT hardcoded user path(s) or machine-specific hostname(s) in source code:"
    echo "$PATH_MATCHES"
    ERRORS_FOUND=$((ERRORS_FOUND + PATH_COUNT))
else
    echo "[✓] Zero hardcoded absolute user paths or machine-specific hostnames found in Kotlin source code."
fi

echo ""
echo "--- 3. Scanning tracked assets for high-confidence secrets ---"
SECRET_FILES=$(git grep -IlE -e '-----BEGIN (RSA|EC|OPENSSH|DSA|PRIVATE) KEY-----|AKIA[0-9A-Z]{16}|(sk|pk|rk)-[A-Za-z0-9]{20,}' -- . ':(exclude)build/**' ':(exclude)node_modules/**' || true)
if [ -n "$SECRET_FILES" ]; then
    SECRET_COUNT=$(printf '%s\n' "$SECRET_FILES" | wc -l | tr -d ' ')
    echo "[!] Found $SECRET_COUNT tracked file(s) containing a high-confidence secret pattern:"
    printf '%s\n' "$SECRET_FILES"
    ERRORS_FOUND=$((ERRORS_FOUND + SECRET_COUNT))
else
    echo "[✓] No high-confidence secret patterns found in tracked assets."
fi

echo ""
echo "--- 4. Scanning code and scripts for actionable TODO/FIXME markers ---"
TODO_MATCHES=$(git grep -n -I -E '\b(TODO|FIXME)\b' -- '*.kt' '*.kts' '*.sh' '*.py' '*.gradle' '*.gradle.kts' | grep -vE '(/build/|improvement-backlog\.md|quality-backlog\.md)' || true)
if [ -n "$TODO_MATCHES" ]; then
    TODO_COUNT=$(printf '%s\n' "$TODO_MATCHES" | wc -l | tr -d ' ')
    echo "[!] Found $TODO_COUNT actionable TODO/FIXME marker(s):"
    printf '%s\n' "$TODO_MATCHES"
    ERRORS_FOUND=$((ERRORS_FOUND + TODO_COUNT))
else
    echo "[✓] No actionable TODO/FIXME markers found in code or scripts."
fi

echo ""
echo "--- 5. Scanning for references to retired symbols ---"
STALE_MATCHES=$(git grep -n -I -E 'refreshUsdBalanceAfterSells|JsModels' -- . ':(exclude)CHANGELOG.md' ':(exclude)build/**' | \
    grep -vE '(^|:)(\.agents/skills/autonomous-code-optimizer/scripts/audit_and_verify\.sh|\.agents/skills/frontend-js-development/SKILL\.md):' || true)
if [ -n "$STALE_MATCHES" ]; then
    STALE_COUNT=$(printf '%s\n' "$STALE_MATCHES" | wc -l | tr -d ' ')
    echo "[!] Found $STALE_COUNT reference(s) to retired symbols outside the historical changelog:"
    printf '%s\n' "$STALE_MATCHES"
    ERRORS_FOUND=$((ERRORS_FOUND + STALE_COUNT))
else
    echo "[✓] No retired-symbol references found outside the historical changelog."
fi

echo ""
echo "--- 6. Running Kotlin Code Formatting & Line-Length Check (Spotless / ktlint) ---"
if ./gradlew spotlessCheck; then
    echo "[✓] Spotless Kotlin code formatting and 120-char line length check passed cleanly."
else
    echo "[!] Spotless code formatting violations found. Run ./gradlew spotlessApply to fix."
    ERRORS_FOUND=$((ERRORS_FOUND + 1))
fi

echo ""
echo "--- 7. Running Markdown Linting across tracked Markdown assets ---"
markdown_files=()
while IFS= read -r file; do
    markdown_files+=("$file")
done < <(git ls-files -- '*.md' '*.mdc')
if npx markdownlint-cli "${markdown_files[@]}"; then
    echo "[✓] Markdown linting passed cleanly."
else
    echo "[!] Markdown linting encountered issues."
    ERRORS_FOUND=$((ERRORS_FOUND + 1))
fi

echo ""
echo "--- 8. Running shell and Python syntax checks ---"
SYNTAX_FAILURES=0
while IFS= read -r file; do
    if ! bash -n "$file"; then
        SYNTAX_FAILURES=$((SYNTAX_FAILURES + 1))
    fi
done < <(git ls-files -- '*.sh')
while IFS= read -r file; do
    if ! python3 - "$file" <<'PY'
import ast
import pathlib
import sys

ast.parse(pathlib.Path(sys.argv[1]).read_text())
PY
    then
        SYNTAX_FAILURES=$((SYNTAX_FAILURES + 1))
    fi
done < <(git ls-files -- '*.py')
if [ "$SYNTAX_FAILURES" -eq 0 ]; then
    echo "[✓] Shell and Python syntax checks passed cleanly."
else
    echo "[!] $SYNTAX_FAILURES shell/Python syntax check(s) failed."
    ERRORS_FOUND=$((ERRORS_FOUND + SYNTAX_FAILURES))
fi

echo ""
echo "--- 9. Running Backend & Frontend Test Suites ---"
if ./.agents/skills/commit-and-push/scripts/pre_commit_check.sh; then
    echo "[✓] Pre-commit build & test suite passed cleanly."
else
    echo "[!] Build or test failures detected."
    ERRORS_FOUND=$((ERRORS_FOUND + 1))
fi

echo ""
echo "================================================="
if [ "$ERRORS_FOUND" -eq 0 ]; then
    echo " RESULT: CLEAN CONVERGENCE (0 Issues Detected)"
else
    echo " RESULT: $ERRORS_FOUND Issue(s) Pending Resolution"
fi
echo "================================================="

exit $ERRORS_FOUND
