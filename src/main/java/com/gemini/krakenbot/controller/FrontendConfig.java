package com.gemini.krakenbot.controller;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.Settings;

import java.util.List;

public record FrontendConfig(Settings settings, List<Allocation> allocations) {
}
