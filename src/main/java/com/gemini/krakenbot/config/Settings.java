package com.gemini.krakenbot.config;

public record Settings(
        long loopDelaySeconds,
        double deviationTriggerPercent,
        boolean dryRun) {
}
