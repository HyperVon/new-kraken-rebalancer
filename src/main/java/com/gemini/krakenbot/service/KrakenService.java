package com.gemini.krakenbot.service;

import java.util.Map;

public interface KrakenService {

    Map<String, Double> getBalances();
    Map<String, Double> getTickerPrices(String pairs);
    void executeOrder(String pair, String type, String side, double volume);

}
