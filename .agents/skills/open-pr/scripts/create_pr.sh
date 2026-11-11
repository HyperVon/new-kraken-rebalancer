#!/usr/bin/env bash
set -e

# Not a shortcut for the open-pr skill workflow (Steps 0-5). This script only
# runs pre_commit_check.sh and opens the PR. Complete change-specific
# verification and the mandatory adversarial review first, then pass a body
# prepared from .agents/skills/open-pr/examples/sample_pr_body.md whose Test
# plan lines are all checked or omitted as N/A. Never claim "PASSED" for a
# check you did not run for this change.

BRANCH=$(git branch --show-current)

if [ "$BRANCH" = "main" ]; then
    echo "[!] Cannot open a Pull Request from 'main'. Please checkout a feature branch."
    exit 1
fi

BODY_FILE="${1:-}"
if [ -z "$BODY_FILE" ] || [ ! -s "$BODY_FILE" ]; then
    echo "[!] Missing or empty PR body file. Usage: create_pr.sh <pr-body-file>"
    echo "    Prepare it from .agents/skills/open-pr/examples/sample_pr_body.md"
    echo "    after Steps 0-5 (change-specific verification + adversarial review)."
    exit 1
fi

if grep -nE '^[[:space:]]*- \[ \]' "$BODY_FILE"; then
    echo "[!] PR body contains unchecked test-plan items. Every item must be"
    echo "    checked ([x]) or omitted as N/A before opening the PR."
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

# Step 3: Title from the latest commit subject
LAST_COMMIT_MSG=$(git log -1 --pretty=%B | head -n 1)
TITLE="$LAST_COMMIT_MSG"

# Step 4: Create PR via gh CLI with the prepared body
echo "--- Step 2: Creating GitHub Pull Request ---"
gh auth setup-git
PR_URL=$(env -u GITHUB_TOKEN gh pr create --base main --head "$BRANCH" --title "$TITLE" --body-file "$BODY_FILE")

echo ""
echo "================================================="
echo " [✓] Pull Request successfully created!"
echo " URL: $PR_URL"
echo "================================================="
