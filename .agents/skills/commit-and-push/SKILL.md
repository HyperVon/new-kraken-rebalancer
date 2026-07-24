---
name: commit-and-push
description: Workflow for finalizing changes — update CHANGELOG/README, run lints and tests, commit, and push to GitHub using `gh` for authentication.
---

# Commit and Push Workflow

When the user asks to "commit and push", "commit / push", or similar, follow this exact workflow in order. Do NOT skip steps.

## Step 0: Git Status & Branch Safety Check

Inspect git working tree state and target branch before modifying documentation or staging:

```bash
git status
git branch --show-current
```

Ensure you are on the intended branch and not on a detached `HEAD`.

## Step 1: Update Documentation

- Update `CHANGELOG.md` with a new version entry following the [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) format.
  - Use the next appropriate semantic version number.
  - Categorize changes under `### Added`, `### Changed`, `### Fixed`, or `### Removed` as appropriate.
- Update `README.md` whenever changes affect features, tech stack, or directory/package structure trees (e.g., updates under `src/main/kotlin/com/gemini/krakenbot/`).
- Update `build.gradle.kts` JaCoCo coverage exclusions (`tasks.jacocoTestReport` and `tasks.jacocoTestCoverageVerification`) whenever new non-tested packages or view/DSL modules are added, moved, or deleted.

## Step 2: Automated Pre-Commit Checks

Run the automated pre-commit script `.agents/skills/commit-and-push/scripts/pre_commit_check.sh` or execute markdown linting and test runs manually:

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Or manually:

```bash
npx markdownlint-cli AGENTS.md CHANGELOG.md README.md docs/*.md
./gradlew test :frontend-js:jsTest
```

Both linting and unit/JS tests must pass with zero failures. Do NOT proceed if tests fail.

## Step 3: Stage and Commit

```bash
git add -A
git commit -m "<type>: <concise description>"
```

Use conventional commit types: `feat`, `fix`, `refactor`, `docs`, `style`, `test`, `build`, `chore`.

## Step 4: Push Using GitHub CLI

**Always** use the `gh` CLI for authentication. The `GITHUB_TOKEN` environment variable may be stale, so unset it:

```bash
gh auth setup-git
env -u GITHUB_TOKEN git push origin main
```

If the push fails due to authentication, run `gh auth status` to diagnose and `gh auth login` to re-authenticate. **Do NOT ask the user to authenticate manually.**

## Step 5: Verify

Confirm the push succeeded by checking `git status` shows the branch is up to date with `origin/main`.
