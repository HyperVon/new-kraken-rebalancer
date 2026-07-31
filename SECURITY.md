# Security Policy

This policy is for users, operators, contributors, and security researchers. It
explains which versions receive security updates, how to report a vulnerability,
and how to operate the application safely.

## Supported versions

Only the latest release on the `main` branch is actively maintained and receives
security updates.

| Version | Supported |
| :--- | :--- |
| Latest (`main`) | :white_check_mark: |
| Older releases | :x: |

## Reporting a vulnerability

Please do not report security vulnerabilities through public GitHub issues.
This application manages cryptocurrency portfolio data and can interact with the
Kraken exchange using credentials that permit live trading.

Use GitHub's
[Private Vulnerability Reporting](https://github.com/HyperVon/new-kraken-rebalancer/security/advisories/new)
to submit a confidential report. The same form is available from the repository's
**Security** tab.

### Information that helps

A useful report includes:

- a description of the vulnerability and its potential impact;
- affected versions or commits;
- reproducible steps or a minimal proof of concept;
- relevant logs or screenshots with credentials and account data removed;
- any known mitigation or suggested fix.

Please exclude Kraken API keys, private keys, balances, transaction details, and
other sensitive account information unless a secure follow-up channel has been
agreed upon.

### Response targets

- **Acknowledgement:** within 48 hours of submission
- **Status update:** within 7 days with an initial severity assessment and
  resolution plan
- **Resolution:** critical vulnerabilities receive priority and are patched as
  quickly as practical

These are response targets rather than guarantees. Complex reports may require
additional investigation or coordination.

## Operating the application securely

### Kraken credential protection

`rebalancer-config.json` can contain Kraken credentials and is intentionally
excluded by `.gitignore`. Do not commit it or attach it to an issue, pull request,
or support request.

The configuration supports environment placeholders in the form
`${ENV_VAR}` or `${ENV_VAR:default}`. For example:

```json
{
  "apiKey": "${KRAKEN_API_KEY:}",
  "privateKey": "${KRAKEN_PRIVATE_KEY:}"
}
```

When credentials remain unchanged, saving unrelated settings preserves the raw
placeholders instead of writing resolved secrets to disk. Credentials entered or
changed through the Settings form are persisted as entered.

Additional precautions include:

- granting only the Kraken permissions needed by the application: **Query
  Funds**, **Query Closed Orders & Trades**, and **Create & Modify Orders**;
- rotating API keys periodically and immediately after suspected exposure;
- using separate credentials for this application rather than reusing keys;
- redacting credentials and account data from logs, screenshots, and bug reports;
- starting with `dryRun: true` or `simulation: true` before considering live
  trading.

Runtime logs can include order identifiers, balance amounts, and asset keys.
Protect them as account data and redact them before sharing. The endpoints used
during normal application operation do not require the **Query Open Orders &
Trades** permission.

### Dashboard trust model

The dashboard and HTTP API do not have user authentication. They are designed
for a single trusted operator on a local machine or private network.

Browser cross-origin access is limited to local and private origins, including
localhost, IPv4 and IPv6 loopback, `.local` hostnames, RFC1918 private ranges,
and the `169.254.0.0/16` link-local range. This CORS policy is not a substitute
for network access control.

The settings mutation also uses a double-submit CSRF token. The Settings page
issues an `HttpOnly`, `SameSite=Strict` cookie and embeds the matching token in
the form; POST requests without both values are rejected. The cookie is not
marked `Secure` because the intended private-network deployment supports HTTP
LAN access. This reduces cross-site form submission risk without requiring
authentication or restricting trusted LAN clients from opening the Settings
page.

For safe operation:

- do not expose port 8080 directly to the public internet;
- restrict network access with host firewall, router, container, or reverse
  proxy controls;
- treat every device that can reach the dashboard as trusted to change
  configuration and potentially initiate live trading;
- add authentication at a trusted reverse proxy before permitting access beyond
  a private single-operator environment.

### Ambiguous live order submissions

Before a real AddOrder request, the application records a durable `PENDING`
intent. The request is attempted once because a network failure can happen after
Kraken has already accepted the order. An automatic retry could duplicate a
filled or already-closed order.

An ambiguous result becomes `UNCERTAIN`, stops the current order batch, and
blocks later live submissions. If this occurs:

1. Stop automated live trading while investigating.
2. Back up `kraken-rebalancer.db` before changing any stored state.
3. Use the recorded local `client_order_id` as Kraken's `cl_ord_id` to locate the
   matching open or closed order and obtain its Kraken order transaction ID.
4. Use that order transaction ID to check TradesHistory fills, which expose
   `ordertxid` rather than `cl_ord_id`.
5. Resolve the stored state only after the exchange outcome is known.

The recommended **Query Closed Orders & Trades** permission covers ClosedOrders
and TradesHistory. Direct REST verification through OpenOrders additionally
requires **Query Open Orders & Trades**, even though the application does not
need that permission during normal operation. If it is needed for an
investigation, prefer a separate read-only diagnostic key or remove the added
permission after reconciliation.

An empty trade-history response is not sufficient proof that Kraken rejected the
order. Unresolved intents are deliberately excluded from heuristic
reconciliation, duplicate cleanup, and age-based pruning.

## Security scope and limitations

- The project does not claim that the dashboard is safe for unrestricted public
  hosting.
- CORS limits browser origins but does not authenticate users or protect a
  publicly reachable process.
- Dry-run mode suppresses order placement but can still use real Kraken account
  data when simulation is disabled.
- Simulation mode uses the offline exchange emulator and does not contact Kraken.
- No software safeguard replaces careful credential management, network
  isolation, backups, and human review before live trading.
