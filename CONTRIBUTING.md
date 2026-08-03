# Contributing to new-kraken-rebalancer

Thank you for your interest in contributing! This project is a production-grade
autonomous portfolio rebalancer for the Kraken exchange. Contributions that
improve reliability, safety, and functionality are very welcome.

## Getting Started

### Prerequisites

- JDK 25+
- Gradle (the `./gradlew` wrapper is included — no separate installation
  required)
- A Kraken account (for testing with real API — use **dry-run mode**)
- Basic familiarity with Kotlin, Ktor, Koin, and kotlinx.html

### Local Setup

1. **Fork and clone** the repository

   ```bash
   git clone https://github.com/YOUR_USERNAME/new-kraken-rebalancer.git
   cd new-kraken-rebalancer
   ```

2. **Create your config file** from the template:

   ```bash
   cp rebalancer-config-template.json rebalancer-config.json
   ```

   Fill in your Kraken API credentials. **Never commit this file.**

3. **Build the project:**

   ```bash
   ./gradlew build -x test
   ```

4. **Run in dry-run mode** before enabling live trading:
   Set `"dryRun": true` in your `rebalancer-config.json`.

## How to Contribute

### Reporting Bugs

Search [existing issues](https://github.com/HyperVon/new-kraken-rebalancer/issues)
first

- Use the **Bug Report** issue template
- Include relevant logs (redact any API keys or account details)

### Suggesting Features

- Open
  a [Feature Request](https://github.com/HyperVon/new-kraken-rebalancer/issues/new/choose)
  issue first to discuss before implementing
- Explain the use case and how it benefits users

### Submitting a Pull Request

1. Create a feature branch from `main`:

   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes, keeping commits focused and descriptive
3. Ensure existing tests and coverage gates pass:

   ```bash
   ./gradlew build jacocoTestCoverageVerification
   ```

   (requires a valid `rebalancer-config.json` for local runs that load config).
   Frontend browser tests are included via `check` → `:frontend-js:jsBrowserTest`.
    **CodeQL** runs Java/Kotlin analysis from `.github/workflows/codeql.yml` on
     `main` pushes and pull requests. The workflow uses CodeQL Action v4.37.4,
     JDK 25, and `build-mode: manual`.
   Agent-assisted PRs should also follow
   [`.agents/skills/open-pr`](.agents/skills/open-pr/SKILL.md) (including the
   mandatory adversarial review). Human contributors can treat that as optional.
4. Open a pull request against `main` with a clear description of what and why

## AI / coding agents

This project was developed through human-directed AI coding sessions. Read the
[Agentic Development Guide](docs/AGENTIC_DEVELOPMENT.md) for its provenance,
cross-harness setup, complete skill catalog, review model, and recommended
human–agent workflow.

Agent guidance is **in the repo** (commit it; do not gitignore `.cursor/`):

| Path | Audience |
| :--- | :--- |
| [`docs/AGENTIC_DEVELOPMENT.md`](docs/AGENTIC_DEVELOPMENT.md) | Humans — development model and onboarding |
| [`.agents/AGENTS.md`](.agents/AGENTS.md) | All agents — stack, invariants, skill index |
| [`.agents/OPERATING.md`](.agents/OPERATING.md) | All agents — always-on operating norms |
| [`.agents/skills/`](.agents/skills/) | All agents — task workflows |
| [`.cursor/rules/`](.cursor/rules/) | Cursor — auto-loaded projections of OPERATING.md |
| [`CLAUDE.md`](CLAUDE.md) | Claude Code — points at `.agents/` |
| [`.github/copilot-instructions.md`](.github/copilot-instructions.md) | GitHub Copilot — points at `.agents/` |

When changing always-on norms, update **OPERATING.md** and the matching
`.cursor/rules/*.mdc` files together.

For Codex, OpenCode, or another harness without a checked-in entrypoint,
explicitly direct it to read `.agents/AGENTS.md`, `.agents/OPERATING.md`, and
the smallest matching skill. Keep new harness adapters thin rather than
duplicating the portable rules.

## Code Guidelines

- **Language:** Kotlin for all development (JVM backend + Kotlin/JS client);
  server-side HTML (kotlinx.html DSL + HTMX) for the dashboard
- **Style:** Follow existing code formatting conventions; use idiomatic Kotlin (
  data classes, coroutines, extension functions). Run
  `./gradlew spotlessApply` (Spotless + ktlint, 120-char line length) before
  opening a PR
- **Safety first:** Any change touching order execution must be tested with
  `dryRun: true`. Keep `dryRun` and `simulation` distinct — see
  [docs/ALGORITHM.md](docs/ALGORITHM.md) and the dry-run skill under `.agents/`.
  Preserve the live-order journal invariant: persist `PENDING` before AddOrder,
  never retry an ambiguous AddOrder response, and block later live submissions
  while `PENDING` / `UNCERTAIN` rows remain
- **No credentials:** Never include API keys, secrets, or real account data in
  commits
- **Tests:** Add or update tests for any non-trivial logic changes. The project
  enforces JaCoCo **95%** line/method/instruction and **90%** branch coverage on
  the JVM, plus Karma/Istanbul **90/90/90/75** on `:frontend-js`
- **Coroutines:** Any method interacting with `KrakenService` must be a
  `suspend` function and tested with `runTest`. Flow-based APIs (e.g.
  `watchConfigChanges()`) should use
  `advanceUntilIdle()` in tests and opt in to `ExperimentalCoroutinesApi` where
  required.
- **Error handling:** Prefer the sealed `Result<T>` type for operations that may
  fail without throwing; use `fold`/`map` at call sites.

## Areas Where Help is Welcome

- Additional exchange support (beyond Kraken)
- Improved rebalancing strategies
- UI/dashboard enhancements
- Test coverage improvements
- Documentation and examples

## Code of Conduct

This project follows
the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By
participating, you agree to uphold these standards.

## Questions?

Open
a [GitHub Discussion](https://github.com/HyperVon/new-kraken-rebalancer/discussions)
or file an issue — happy to help get you set up.
