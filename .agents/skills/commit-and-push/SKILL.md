---
name: commit-and-push
description: Workflow for finalizing changes — update CHANGELOG/README, run lints and tests, commit, and push to GitHub using `gh` for authentication.
---

# Commit and Push Workflow

When the user asks to "commit and push", "commit / push", or similar, follow this
exact workflow in order. Do NOT skip steps.

## Step 1: Update Documentation

- Update `CHANGELOG.md` with a new version entry following the
  [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) format.
  - Use the next appropriate semantic version number.
  - Categorize changes under `### Added`, `### Changed`, `### Fixed`, or
    `### Removed` as appropriate.
- Update `README.md` if the changes affect features, tech stack, or
  documentation described there.

## Step 2: Run Markdown Linting

Run `npx markdownlint-cli` on all modified `.md` files and fix any errors:

```bash
npx markdownlint-cli AGENTS.md CHANGELOG.md README.md docs/*.md
```

If errors are found, fix them before proceeding. Common issues:

- Missing blank lines around headings (`MD022`)
- Missing blank lines around lists (`MD032`)
- Missing blank lines around fenced code blocks (`MD031`)

## Step 3: Run Tests

Run the full test suite to verify nothing is broken:

```bash
./gradlew test :frontend-js:jsTest
```

Both must pass. Do NOT proceed if tests fail.

## Step 4: Stage and Commit

```bash
git add -A
git commit -m "<type>: <concise description>"
```

Use conventional commit types: `feat`, `fix`, `refactor`, `docs`, `style`,
`test`, `build`, `chore`.

## Step 5: Push Using GitHub CLI

**Always** use the `gh` CLI for authentication. The `GITHUB_TOKEN` environment
variable may be stale, so unset it:

```bash
gh auth setup-git
env -u GITHUB_TOKEN git push origin main
```

If the push fails due to authentication, run `gh auth status` to diagnose and
`gh auth login` to re-authenticate. **Do NOT ask the user to authenticate
manually.**

## Step 6: Verify

Confirm the push succeeded by checking `git status` shows the branch is up to
date with `origin/main`.
