---
description: "Run the repository quality gates without changing files"
---

# Quality Gate

Run a read-only verification pass for this repository.

- Read the repository's `gradle-quality-gates` skill before choosing commands.
- Do not read or print `rebalancer-config.json`, `.env` files, database files, logs, home-directory files, or any other local runtime data.
- Do not edit, format, delete, commit, push, or start a long-running application process.
- Run the full JVM, coverage, frontend, and formatting checks:
  - `./gradlew build jacocoTestCoverageVerification`
  - `npx markdownlint-cli AGENTS.md .clinerules/**/*.md .cursor/rules/*.mdc .agents/AGENTS.md .agents/OPERATING.md CLAUDE.md .github/copilot-instructions.md CHANGELOG.md CONTRIBUTING.md README.md SECURITY.md docs/*.md .agents/skills/**/*.md .kilo/command/*.md .kilo/agent/**/*.md`
- Report each command as pass or fail, identify the first actionable failure and its file/task, and summarize successful checks.
- Redact credentials, tokens, account identifiers, hostnames, personal paths, and personal or account data from command output.

Do not make fixes in this command. This command is for evidence before a separate implementation or formatting pass.
