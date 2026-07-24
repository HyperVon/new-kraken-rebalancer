#!/usr/bin/env bash
set -e

echo "================================================="
echo "   AUTONOMOUS CODE OPTIMIZER — AUDIT & SCAN"
echo "================================================="

ERRORS_FOUND=0

echo ""
echo "--- 1. Scanning for inline FQNs in Kotlin sources ---"
FQN_MATCHES=$(grep -rn "com\.gemini\.krakenbot\." src/ common/ frontend-js/ --include="*.kt" --include="*.kts" 2>/dev/null | grep -v ":package " | grep -v ":import " | grep -v "/build/" | grep -v ":\s*\*" | grep -v ":\s*//" | grep -v "Class\.forName" || true)
if [ -n "$FQN_MATCHES" ]; then
    FQN_COUNT=$(echo "$FQN_MATCHES" | wc -l | tr -d ' ')
    echo "[!] Found $FQN_COUNT inline FQN reference(s) in Kotlin source code:"
    echo "$FQN_MATCHES"
    ERRORS_FOUND=$((ERRORS_FOUND + FQN_COUNT))
else
    echo "[✓] Zero inline FQNs found in Kotlin source files."
fi

echo ""
echo "--- 2. Scanning for hardcoded user paths (/Users/) and machine-specific hostnames in source files ---"
PATH_MATCHES=$(grep -rnE "/Users/|macbook|charles-pc" src/ common/ frontend-js/ --include="*.kt" --include="*.kts" 2>/dev/null | grep -v "/build/" || true)
if [ -n "$PATH_MATCHES" ]; then
    PATH_COUNT=$(echo "$PATH_MATCHES" | wc -l | tr -d ' ')
    echo "[!] Found $PATH_COUNT hardcoded user path(s) or machine-specific hostname(s) in source code:"
    echo "$PATH_MATCHES"
    ERRORS_FOUND=$((ERRORS_FOUND + PATH_COUNT))
else
    echo "[✓] Zero hardcoded absolute user paths or machine-specific hostnames found in Kotlin source code."
fi

echo ""
echo "--- 3. Running Kotlin Code Formatting & Line-Length Check (Spotless / ktlint) ---"
if ./gradlew spotlessCheck; then
    echo "[✓] Spotless Kotlin code formatting and 120-char line length check passed cleanly."
else
    echo "[!] Spotless code formatting violations found. Run ./gradlew spotlessApply to fix."
    ERRORS_FOUND=$((ERRORS_FOUND + 1))
fi

echo ""
echo "--- 4. Running Markdown Linting ---"
if npx markdownlint-cli .agents/AGENTS.md CHANGELOG.md README.md docs/*.md .agents/skills/**/SKILL.md .agents/skills/**/*.md; then
    echo "[✓] Markdown linting passed cleanly."
else
    echo "[!] Markdown linting encountered issues."
    ERRORS_FOUND=$((ERRORS_FOUND + 1))
fi

echo ""
echo "--- 4. Running Backend & Frontend Test Suites ---"
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
