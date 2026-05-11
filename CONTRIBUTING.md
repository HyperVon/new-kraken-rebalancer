# Contributing to new-kraken-rebalancer

Thank you for your interest in contributing! This project is a production-grade autonomous portfolio rebalancer for the Kraken exchange. Contributions that improve reliability, safety, and functionality are very welcome.

## Getting Started

### Prerequisites

- Java 25+
- Maven 3.8+
- A Kraken account (for testing with real API — use **dry-run mode**)
- Basic familiarity with Spring Boot and Kotlin

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
   mvn clean install -DskipTests
   ```

4. **Run in dry-run mode** before enabling live trading:
   Set `"dryRun": true` in your `rebalancer-config.json`.

## How to Contribute

### Reporting Bugs

- Search [existing issues](https://github.com/HyperVon/new-kraken-rebalancer/issues) first
- Use the **Bug Report** issue template
- Include relevant logs (redact any API keys or account details)

### Suggesting Features

- Open a [Feature Request](https://github.com/HyperVon/new-kraken-rebalancer/issues/new/choose) issue first to discuss before implementing
- Explain the use case and how it benefits users

### Submitting a Pull Request

1. Create a feature branch from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```
2. Make your changes, keeping commits focused and descriptive
3. Ensure existing tests pass: `mvn test` (requires a valid `rebalancer-config.json`)
4. Open a pull request against `main` with a clear description of what and why

## Code Guidelines

- **Language:** Kotlin preferred for new code; Java acceptable
- **Style:** Follow existing code formatting conventions
- **Safety first:** Any change touching order execution must be tested with `dryRun: true`
- **No credentials:** Never include API keys, secrets, or real account data in commits
- **Tests:** Add or update tests for any non-trivial logic changes

## Areas Where Help is Welcome

- Additional exchange support (beyond Kraken)
- Improved rebalancing strategies
- UI/dashboard enhancements
- Test coverage improvements
- Documentation and examples

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold these standards.

## Questions?

Open a [GitHub Discussion](https://github.com/HyperVon/new-kraken-rebalancer/discussions) or file an issue — happy to help get you set up.
