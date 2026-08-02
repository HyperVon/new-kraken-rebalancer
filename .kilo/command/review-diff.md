---
description: "Review current changes against repository safety and quality rules"
---

# Review Diff

Perform a read-only review of the current working-tree changes.

- Read `.agents/AGENTS.md`, `.agents/OPERATING.md`, the `code-review` skill, and any domain skill that matches the changed files.
- Inspect `git status --short`, `git diff --check`, the unstaged diff, the staged diff, and relevant untracked files.
- Review the full changed surface for correctness, regression risk, missing tests, dry-run versus simulation mistakes, secret handling, persistence safety, and repository conventions.
- Do not read or print `rebalancer-config.json`, `.env` files, database files, logs, home-directory files, or unrelated external files.
- Do not edit, format, delete, commit, push, or run commands that mutate application data.
- Never reproduce credentials, tokens, account identifiers, personal paths, hostnames, or personal or account data from the diff or tool output. Describe any exposure without quoting the value.
- Report findings first, ordered by severity, with `path:line` references and a concrete impact. State explicitly when no findings were found, then list residual testing gaps.

This command is a local project-specific pre-pass. It does not replace the
repository's mandatory adaptive bounded adversarial review before opening or
updating a pull request.
