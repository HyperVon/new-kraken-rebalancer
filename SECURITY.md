# Security Policy

## Supported Versions

Only the latest release on the `main` branch is actively maintained and receives
security updates.

| Version        | Supported          |
|----------------|--------------------|
| latest (main)  | :white_check_mark: |
| older releases | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

This project manages live cryptocurrency portfolio data and interacts with the
Kraken exchange API. Security vulnerabilities should be treated with care.

### How to Report

Please use GitHub's
**[Private Vulnerability Reporting](https://github.com/HyperVon/new-kraken-rebalancer/security/advisories/new)**
feature to submit a vulnerability report confidentially.

Alternatively, you may open a GitHub Security Advisory directly from the
**Security** tab of this repository.

### What to Include

Please include as much of the following as possible:

- A description of the vulnerability and its potential impact
- Steps to reproduce the issue
- Any relevant logs, screenshots, or proof-of-concept code
- Suggested fix or mitigation (if known)

### What to Expect

- **Acknowledgement**: Within 48 hours of submission
- **Status update**: Within 7 days with an assessment of severity and planned
  resolution
- **Resolution**: Critical vulnerabilities will be prioritized and patched as
  quickly as possible

## Security Considerations for Users

This application handles sensitive Kraken API credentials and executes live
trades. When deploying:

- **Never commit your `rebalancer-config.json`** — it contains your API keys and
  is listed in `.gitignore` for this reason
- Run with the **minimum required API permissions** on Kraken (Query Funds,
  Query Closed Orders & Trades, Create & Modify Orders). Query Open Orders is
  not required by the endpoints this application uses.
- Consider running in **dry-run mode** (`dryRun: true`) before enabling live
  trading
- Restrict access to the machine running this application
- Regularly rotate your Kraken API keys

### Dashboard trust model

The web dashboard and HTTP API have **no user authentication**. Security relies
on **local / private-network trust**:

- CORS only allows origins that pass `isLocalOrPrivateOrigin` (`localhost`,
  `127.0.0.1`, `*.local`, RFC1918 private ranges, link-local `169.254.*`).
  Literal `::1` is **not** effectively allowlisted today because host parsing
  uses `substringBefore(":")`, which empties IPv6 addresses.
- Do **not** expose port 8080 to the public internet
- Prefer binding/access only from the host or trusted LAN devices that can reach
  the process

Treat any machine that can open the dashboard as fully trusted for config
changes and (if `dryRun` / `simulation` are off) live trading.
