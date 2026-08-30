#!/usr/bin/env bash
set -e

echo "=== Scanning for inline FQNs in Kotlin sources ==="
if command -v rg >/dev/null 2>&1; then
    rg "com\.gemini\.krakenbot\.[A-Za-z0-9\.]+" backend/ common/ frontend-js/ engine/ codegen/ || echo "No inline FQNs found."
else
    grep -rn "com.gemini.krakenbot." backend/ common/ frontend-js/ engine/ codegen/ || echo "No inline FQNs found."
fi

echo "=== Scanning for hardcoded absolute user paths (/Users/) ==="
if command -v rg >/dev/null 2>&1; then
    rg "/Users/" backend/ common/ frontend-js/ engine/ codegen/ || echo "No hardcoded absolute user paths found."
else
    grep -rn "/Users/" backend/ common/ frontend-js/ engine/ codegen/ || echo "No hardcoded absolute user paths found."
fi

echo "=== Anti-pattern scan complete ==="
