# Kraken Rebalancer

A robust, automated portfolio rebalancing bot for the Kraken cryptocurrency exchange. This application monitors your portfolio and automatically creates buy/sell orders to maintain your desired asset allocation percentages.

## Features

-   **Automatic Rebalancing**: Continuously monitors your portfolio and rebalances when asset allocations drift beyond a configured threshold.
-   **Fiat Correction**: Intelligently handles deposits and withdrawals by distributing surplus USD (or selling assets for withdrawals) based on your target allocation logic.
-   **Drift Protection**: Prevents oscillation by using a configurable deviation threshold.
-   **Dry Run Mode**: safely test your configuration and strategy without executing real trades.
-   **Java Spring Boot**: Built on a modern, enterprise-grade stack.

## Documentation

-   **[Algorithm Details](ALGORITHM.md)**: A detailed explanation of the logic, trigger conditions, and execution phases.

## Getting Started

### Prerequisites

-   Java 21 or higher
-   Maven
-   A Kraken account with API Keys (Permissions: Query Funds, Modified Orders)

### Configuration

1.  Copy the template config:
    ```bash
    cp rebalancer-config-template.json rebalancer-config.json
    ```
2.  Edit `rebalancer-config.json`:
    *   Add your Kraken API Key and Private Key.
    *   Define your desired `allocations` (Must sum to 100%).
    *   Set `dryRun` to `true` for initial testing.

### Running the Application

Run the application using Maven:

```bash
mvn spring-boot:run
```

The application will start logging the portfolio status and any actions taken.
