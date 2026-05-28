# Kraken Rebalancer — Frontend

A React-based dashboard for monitoring and configuring the Kraken Rebalancer.

## Tech Stack

- **React 19** with **TypeScript** and Vite 8 for type-safe, fast HMR development
- **Chart.js** (via react-chartjs-2) for portfolio allocation visualization
- **Tailwind CSS v4** for utility-first responsive styling and theme configuration

## Components

| Component | Description |
|---|---|
| `Dashboard` | Main view — status cards, allocation chart, asset table, and trade history. Subscribes to `/api/status/stream` via SSE for real-time snapshots, and automatically syncs the trade log. |
| `StatusCard` | Reusable card displaying a metric (Total Portfolio, Cash, Crypto Assets) with optional sub-values. |
| `AllocationChart` | Horizontal bar chart showing top assets by USD value using Chart.js. |
| `TradeHistory` | Scrollable table of recent rebalance cycle actions with BUY/SELL/INFO badges. |
| `Settings` | Configuration editor for global parameters and per-asset allocation targets. Validates 100% total before allowing save. |

## Development

```bash
npm install   # First time setup, or after pulling updates to install new dependencies
npm run dev   # Starts dev server on http://localhost:5173
```

The Vite dev server proxies `/api/*` requests to `http://localhost:8080` (the Ktor backend).

## Build

```bash
npm run build   # Outputs to dist/
```

## Testing

```bash
npm run test           # Run tests in watch mode
npm run test:coverage  # Run tests with coverage reporting
```

The frontend uses **Vitest** and **React Testing Library** for unit and integration testing. Coverage is enforced in CI to be at least **95%** across statements, branches, functions, and lines (currently at **100% statements, 100% lines, 100% functions, and >99% branch coverage**).

