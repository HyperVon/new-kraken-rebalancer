package com.gemini.krakenbot.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    @Test
    void testAssetSnapshot() {
        PortfolioSnapshot.AssetSnapshot asset = new PortfolioSnapshot.AssetSnapshot();
        asset.setSymbol("BTC");
        asset.setBalance(BigDecimal.TEN);
        asset.setPrice(BigDecimal.ONE);
        asset.setValueUSD(BigDecimal.TEN);
        asset.setTargetPercent(BigDecimal.ONE);
        asset.setCurrentPercent(BigDecimal.ONE);
        asset.setDeviationPercent(BigDecimal.ZERO);

        assertEquals("BTC", asset.getSymbol());
        assertEquals(BigDecimal.TEN, asset.getBalance());
        assertEquals(BigDecimal.ONE, asset.getPrice());
        assertEquals(BigDecimal.TEN, asset.getValueUSD());
        assertEquals(BigDecimal.ONE, asset.getTargetPercent());
        assertEquals(BigDecimal.ONE, asset.getCurrentPercent());
        assertEquals(BigDecimal.ZERO, asset.getDeviationPercent());

        PortfolioSnapshot.AssetSnapshot asset2 = new PortfolioSnapshot.AssetSnapshot(
                "ETH", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
        assertEquals("ETH", asset2.getSymbol());
    }
}
