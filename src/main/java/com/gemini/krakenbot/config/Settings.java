package com.gemini.krakenbot.config;

public record Settings(
                long loopDelaySeconds,
                double deviationTriggerPercent,
                Double dustThresholdUSD,
                boolean dryRun) {

        public Settings {
                if (dustThresholdUSD == null) {
                        dustThresholdUSD = 5.0;
                }
        }

}
