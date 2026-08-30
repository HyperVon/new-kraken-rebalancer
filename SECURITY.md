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
  "kraken": {
    "apiKey": "${KRAKEN_API_KEY:}",
    "privateKey": "${KRAKEN_PRIVATE_KEY:}"
  }
}
```

When credentials remain unchanged, saving unrelated settings preserves the raw
placeholders instead of writing resolved secrets to disk. Credentials are
configured in `rebalancer-config.json` or through environment placeholders; the
Settings form does not edit credentials and preserves the current `kraken` block
when saving other settings.

Additional precautions include:

- granting only the Kraken permissions needed by the application: **Query
  Funds**, **Query Closed Orders & Trades**, **Query Ledgers** (Kraken UI:
  *Data - Query ledger entries*), and **Create & Modify Orders**;
- rotating API keys periodically and immediately after suspected exposure;
- using separate credentials for this application rather than reusing keys;
- redacting credentials and account data from logs, screenshots, and bug reports;
- starting with `dryRun: true` or `simulation: true` before considering live
  trading.

Runtime logs can include order identifiers, balance amounts, and asset keys.
Protect them as account data and redact them before sharing. The endpoints used
during normal application operation do not require the **Query Open Orders &
Trades** permission (though manual REST reconciliation of an `UNCERTAIN` live
order requires it if querying open orders directly).

### Dashboard trust model

The dashboard and HTTP API do not have user authentication. They are designed
for a single trusted operator on a local machine or private network.

Browser cross-origin access is limited to local and private origins, including
localhost, IPv4 and IPv6 loopback, `.local` hostnames, RFC1918 private ranges,
and the `169.254.0.0/16` link-local range. This CORS policy is not a substitute
for network access control.

Settings, operator loop-control, and live-order-resolution mutations use a
double-submit CSRF token. The
Settings page issues an `HttpOnly`, `SameSite=Strict` cookie and embeds the
matching token in forms; POST requests without both values are rejected. This
covers `/settings`, `/api/pause`, `/api/resume`, and
`/api/order-intents/{id}/resolve`. The cookie is not marked
`Secure` because the intended private-network deployment supports HTTP LAN
access. This reduces cross-site form submission risk without requiring
authentication or restricting trusted LAN clients from opening the Settings
page.

For safe operation:

- do not expose port 8080 directly to the public internet;
- restrict network access with host firewall, router, container, or reverse
  proxy controls. The server binds the IPv6 wildcard on dual-stack kernels
  (`::`) and accepts IPv4-mapped clients on the same socket, so host firewall
  rules must cover both IPv4 (`iptables`/`netsh advfirewall`) and IPv6
  (`ip6tables`/the IPv6 profile in Windows Defender Firewall) on the same host.
  Hosts without an IPv6 firewall commonly accept global IPv6 traffic by default;
- treat every device that can reach the dashboard as trusted to change
  configuration and potentially initiate live trading;
- add authentication at a trusted reverse proxy before permitting access beyond
  a private single-operator environment.

### Ambiguous live order submissions

Before a real AddOrder request, the application records a durable `PENDING`
row in the SQLite `order_intents` journal. The request is attempted once because a network failure can happen after
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
5. Resolve the stored state only after the exchange outcome is known. Review
   unresolved rows with `GET /api/order-intents`, then submit
   `POST /api/order-intents/{id}/resolve` with `state=CONFIRMED` or
   `state=REJECTED`, a concise evidence note, the optional `orderTxid`, and the
   normal CSRF token.
   A `PENDING` row remains protected while its AddOrder may still be in flight;
   wait for it to become `UNCERTAIN` (or for restart recovery to mark it
   uncertain) before resolving it.

#### Operator recovery runbook

Resolving an intent is a deliberate local-recovery action, not an order retry.
The endpoint updates the application's SQLite journal and its associated local
trade record; it does **not** submit, cancel, or change an order at Kraken.
Operators can inspect and resolve unresolved intents directly via the Dashboard
Action Required banner and form or programmatically via the
`POST /api/order-intents/{id}/resolve` endpoint.

Before sending a resolution request:

1. Pause the loop in the dashboard and leave it paused throughout the review.
2. Back up `kraken-rebalancer.db`.
3. Fetch `GET /api/order-intents` and confirm the exact row is `UNCERTAIN`.
   Do not resolve a `PENDING` row: an AddOrder request may still be in flight.
4. Match the row's `clientOrderId`, pair, side, volume, and timestamp to Kraken.
   For a fill, obtain the Kraken order transaction ID from Closed Orders and
   confirm its fills in Trades History. Do not treat an empty history response
   as proof that the exchange rejected an order.

For a confirmed fill, send the order transaction ID even though the endpoint
accepts it as optional. It preserves the evidence needed to reconcile the
local record with the authoritative exchange fill.

The following macOS/Linux `curl` example requests a CSRF token from the
Settings page, keeps the matching cookie, then resolves intent `42`. Replace
the placeholder base URL, intent ID, Kraken transaction ID, and evidence with
the values you independently verified. It makes no Kraken API call.

```sh
APP_BASE='http://127.0.0.1:8080'
INTENT_ID='42'
ORDER_TXID='KRAKEN-ORDER-TXID'
COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

SETTINGS_HTML="$(curl --fail --silent --show-error \
  --cookie-jar "$COOKIE_JAR" "$APP_BASE/settings")"
CSRF_TOKEN="$(printf '%s' "$SETTINGS_HTML" \
  | sed -n 's/.*name="csrfToken" value="\([^"]*\)".*/\1/p' \
  | head -n 1)"
test -n "$CSRF_TOKEN" || { echo 'Could not obtain a CSRF token.' >&2; exit 1; }

curl --fail-with-body --silent --show-error \
  --cookie "$COOKIE_JAR" \
  --request POST "$APP_BASE/api/order-intents/$INTENT_ID/resolve" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "csrfToken=$CSRF_TOKEN" \
  --data-urlencode 'state=CONFIRMED' \
  --data-urlencode "orderTxid=$ORDER_TXID" \
  --data-urlencode 'evidence=Closed Orders and Trades History confirm the matching Kraken fill.'
```

A successful request returns HTTP `200` and JSON like
`{"resolved":true,"id":42,"state":"CONFIRMED"}`. For a definitively
rejected order, change `state` to `REJECTED`, omit `orderTxid` unless one exists,
and record the specific negative exchange evidence. Never infer rejection from
silence or from the application's provisional local estimate.

Some historical `API_FILL` rows may predate persistence of Kraken's order and
trade identifiers. During a manually confirmed resolution, the application
accepts such a row only when exactly one unkeyed API fill matches the intent's
pair, symbol, side, volume, and a timestamp within ±10 seconds of the intent,
plus 1% USD tolerance and, when available, 1% expected-price tolerance. If the
unkeyed fallback is evaluated and multiple candidates match, the request
returns HTTP `409` and leaves the intent unresolved for further
investigation.
This manual fallback is deliberately stricter than background sync: every
listed constraint must pass, and pair aliases or exact-volume overrides do not
qualify a candidate.

After a successful resolution response, while the loop is still paused:

1. Re-fetch `GET /api/order-intents`; the resolved ID must no longer appear.
2. Check `GET /api/health`; `unresolvedOrderIntents` must decrease. It reaches
   `0` only when no other intent remains unresolved. Readiness remains `PAUSED`
   (and `/api/readiness` returns `503`) until you resume, which is expected.
3. Inspect the matching history entry. When the exact Kraken API fill has
   already synced, confirmation keeps that fill and removes only its duplicate
   local failed estimate.
4. Resume from the dashboard only after all checks pass and no other safety
   condition remains.

The recommended **Query Closed Orders & Trades** permission covers ClosedOrders
and TradesHistory. Direct REST verification through OpenOrders additionally
requires **Query Open Orders & Trades**, even though the application does not
need that permission during normal operation. If it is needed for an
investigation, prefer a separate read-only diagnostic key or remove the added
permission after reconciliation.

An empty trade-history response is not sufficient proof that Kraken rejected the
order. Unresolved intents are deliberately excluded from heuristic
reconciliation, duplicate cleanup, and age-based pruning.

`GET /api/readiness` returns `503` while an unresolved intent exists, while the
loop is paused or stopped, before a snapshot has been produced, after a cycle
failure, or when configuration is unavailable. `/api/health` remains a `200`
liveness/diagnostic endpoint so monitoring can still report the reason for
non-readiness.

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
