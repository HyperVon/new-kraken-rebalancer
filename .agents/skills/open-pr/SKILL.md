---
name: open-pr
description: Workflow for opening structured GitHub Pull Requests using GitHub CLI (gh pr create) with pre-PR quality gate checks, automated commit log summarization, and markdown PR body templates.
---

# Open Pull Request Skill

Use this skill when opening a new GitHub Pull Request for the current working branch. It ensures pre-PR quality checks pass, formats conventional PR titles and structured markdown descriptions, and executes `gh pr create` seamlessly.

## Step-by-Step Workflow

### Step 0: Branch & Remote Check

Verify active branch name and remote status:

```bash
git branch --show-current
git status
```

- **Guard**: Do NOT open a PR from `main`. Ensure you are on a feature/refactor branch (e.g. `refactor/css-styles-cleanup`).
- **Guard**: Ensure all local commits are pushed to `origin` before creating the PR (`git push origin <branch>`).

### Step 1: Check Active PRs

Check if a Pull Request is already open for the current branch:

```bash
gh pr list --head $(git branch --show-current)
```

If a PR already exists, output its URL to the user instead of opening a duplicate PR.

### Step 2: Run Pre-PR Quality Verification

Execute the pre-commit check script to ensure all tests and linting pass:

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Both backend JVM tests (`./gradlew test`), JaCoCo coverage verification, markdown linting, and client Kotlin/JS Karma tests MUST pass with zero errors.

### Step 3: Format PR Title and Markdown Body

1. **PR Title**: Use Conventional Commits format (`feat: ...`, `refactor: ...`, `fix: ...`).
2. **PR Body Structure**: Use the standardized template in `examples/sample_pr_body.md`:
   - **Overview**: 2-3 sentence high-level summary of the PR.
   - **Changes Breakdown**: Grouped bullet points detailing modified/added components.
   - **Verification Results**: Test pass status, JaCoCo coverage metrics, and linting results.

### Step 4: Create Pull Request via GitHub CLI

Execute `gh pr create` using GitHub CLI authentication:

```bash
gh auth setup-git
env -u GITHUB_TOKEN gh pr create --base main --head $(git branch --show-current) --title "<title>" --body "<body>"
```

Or run the automated helper script:

```bash
./.agents/skills/open-pr/scripts/create_pr.sh
```

### Step 5: Output PR Link to User

Provide the resulting GitHub Pull Request URL in a clickable link format to the user.

---

## Review Completion Checklist

Before finalizing:

- [ ] Branch is pushed to remote origin (`git push origin <branch>`)
- [ ] Active branch is not `main`
- [ ] Pre-PR verification script passed cleanly (`pre_commit_check.sh`)
- [ ] PR title follows Conventional Commits format
- [ ] PR body contains structured markdown description
- [ ] Executed `gh pr create` using GitHub CLI
