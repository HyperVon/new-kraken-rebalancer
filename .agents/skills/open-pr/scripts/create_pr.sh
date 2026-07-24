#!/usr/bin/env bash
set -e

BRANCH=$(git branch --show-current)

if [ "$BRANCH" = "main" ]; then
    echo "[!] Cannot open a Pull Request from 'main'. Please checkout a feature branch."
    exit 1
fi

echo "================================================="
echo "   OPENING PULL REQUEST FOR BRANCH: $BRANCH"
echo "================================================="

# Step 1: Check existing PR
EXISTING_PR=$(gh pr list --head "$BRANCH" --json url --jq '.[0].url' 2>/dev/null || true)
if [ -n "$EXISTING_PR" ] && [ "$EXISTING_PR" != "null" ]; then
    echo "[✓] Pull Request already open for branch '$BRANCH':"
    echo "$EXISTING_PR"
    exit 0
fi

# Step 2: Run pre-commit checks
echo "--- Step 1: Running Pre-PR Quality Verification ---"
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh

# Step 3: Extract commit summary and CHANGELOG for PR body
LAST_COMMIT_MSG=$(git log -1 --pretty=%B | head -n 1)
TITLE="$LAST_COMMIT_MSG"

COMMIT_LOGS=$(git log origin/main..HEAD --pretty=format:"- %s")

CHANGELOG_SUMMARY=$(sed -n '/^## \[/!d; p; q' CHANGELOG.md 2>/dev/null || echo "")
LATEST_VERSION_BLOCK=$(awk '/^## \[/{if (count++) exit} count{print}' CHANGELOG.md 2>/dev/null || echo "")

BODY=$(cat <<EOF
## Overview

Pull Request created automatically for branch \`$BRANCH\`.

## Commits Included

$COMMIT_LOGS

## Release Notes (\`CHANGELOG.md\`)

$LATEST_VERSION_BLOCK

## Verification Results

- **Markdown Linting**: PASSED
- **JVM Backend Unit Tests**: PASSED
- **JaCoCo Coverage Verification**: PASSED
- **Kotlin/JS Client Tests**: PASSED
EOF
)

# Step 4: Create PR via gh CLI
echo "--- Step 2: Creating GitHub Pull Request ---"
gh auth setup-git
PR_URL=$(env -u GITHUB_TOKEN gh pr create --base main --head "$BRANCH" --title "$TITLE" --body "$BODY")

echo ""
echo "================================================="
echo " [✓] Pull Request successfully created!"
echo " URL: $PR_URL"
echo "================================================="
