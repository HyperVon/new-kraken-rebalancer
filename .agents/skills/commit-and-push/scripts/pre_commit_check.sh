#!/usr/bin/env bash
set -e

echo "=== Step 1: Running Markdown Linting ==="
npx markdownlint-cli AGENTS.md CHANGELOG.md README.md docs/*.md .agents/skills/**/SKILL.md

echo "=== Step 2: Running JVM Backend Tests ==="
./gradlew test

echo "=== Step 3: Running Client-Side Kotlin/JS Tests ==="
./gradlew :frontend-js:jsTest

echo "=== All pre-commit checks PASSED successfully ==="
