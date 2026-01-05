package com.gemini.krakenbot.config;

import java.util.List;

public record AppConfig(
    KrakenCredentials kraken,
    Settings settings,
    List<Allocation> allocations
) {}
