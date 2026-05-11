# Kraken Rebalancer — Frontend

A React-based dashboard for monitoring and configuring the Kraken Rebalancer.

## Tech Stack

- **React 19** with Vite 7 for fast HMR development
- **Chart.js** (via react-chartjs-2) for portfolio allocation visualization
- **Vanilla CSS** with CSS custom properties for a consistent dark theme

## Components

| Component | Description |
|---|---|
| `Dashboard` | Main view — status cards, allocation chart, asset table, and trade history. Polls `/api/status` and `/api/history` every 5 seconds. |
| `StatusCard` | Reusable card displaying a metric (Total Portfolio, Cash, Crypto Assets) with optional sub-values. |
| `AllocationChart` | Horizontal bar chart showing top assets by USD value using Chart.js. |
| `TradeHistory` | Scrollable table of recent rebalance cycle actions with BUY/SELL/INFO badges. |
| `Settings` | Configuration editor for global parameters and per-asset allocation targets. Validates 100% total before allowing save. |

## Development

```bash
npm install   # First time only
npm run dev   # Starts dev server on http://localhost:5173
```

The Vite dev server proxies `/api/*` requests to `http://localhost:8080` (the Spring Boot backend).

## Build

```bash
npm run build   # Outputs to dist/
```
