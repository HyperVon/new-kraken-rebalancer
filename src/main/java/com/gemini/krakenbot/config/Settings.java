package com.gemini.krakenbot.config;

public record Settings(
                long loopDelaySeconds,
                double deviationTriggerPercent,
                Double dustThresholdUSD,
                boolean dryRun,
                Double fiatMaxDrawdown,
                Double fiatDeploymentExponent) {

        public Settings {
                if (dustThresholdUSD == null) {
                        dustThresholdUSD = 5.0;
                }
                if (fiatMaxDrawdown == null) {
                        fiatMaxDrawdown = 0.0; // Disabled by default
                }
                if (fiatDeploymentExponent == null) {
                        fiatDeploymentExponent = 1.0;
                }
        }

}
