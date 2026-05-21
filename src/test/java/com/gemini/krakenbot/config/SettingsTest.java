package com.gemini.krakenbot.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    @Test
    void constructor_defaultsNullValues() {
        Settings settings = new Settings(10L, 1.5, null, true, null, null);
        assertEquals(5.0, settings.dustThresholdUSD());
        assertEquals(0.0, settings.fiatMaxDrawdown());
        assertEquals(1.0, settings.fiatDeploymentExponent());
        assertTrue(settings.dryRun());
        assertEquals(10L, settings.loopDelaySeconds());
        assertEquals(1.5, settings.deviationTriggerPercent());
    }

    @Test
    void constructor_retainsNonNullValues() {
        Settings settings = new Settings(20L, 2.5, 10.0, false, 15.0, 2.0);
        assertEquals(10.0, settings.dustThresholdUSD());
        assertEquals(15.0, settings.fiatMaxDrawdown());
        assertEquals(2.0, settings.fiatDeploymentExponent());
        assertFalse(settings.dryRun());
        assertEquals(20L, settings.loopDelaySeconds());
        assertEquals(2.5, settings.deviationTriggerPercent());
    }
}
