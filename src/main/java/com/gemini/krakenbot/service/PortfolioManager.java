package com.gemini.krakenbot.service;

public interface PortfolioManager {

    @SuppressWarnings("unused")
    void stopRebalancingLoop();
    void startRebalancingLoop();

}
