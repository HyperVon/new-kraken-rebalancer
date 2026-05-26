package com.gemini.krakenbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.model.PortfolioStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SerializationParityTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shouldParseLegacyJavaPortfolioStatsJsonAccurately() throws Exception {
        String legacyJson = """
            {
              "allTimeHigh": 123456.789101112
            }
        """;

        PortfolioStats parsed = mapper.readValue(legacyJson, PortfolioStats.class);
        assertNotNull(parsed.getAllTimeHigh());
        assertEquals(0, parsed.getAllTimeHigh().compareTo(new BigDecimal("123456.789101112")));
    }

    @Test
    void shouldParseLegacyJavaPortfolioSnapshotJsonAccurately() throws Exception {
        String legacyJson = """
            [
              {
                "timestamp": 1672567200.000000000,
                "totalValueUSD": 15000.50,
                "assets": {
                  "XXBTZUSD": {
                    "symbol": "XXBTZUSD",
                    "balance": 0.5,
                    "price": 20000.0,
                    "valueUSD": 10000.0,
                    "targetPercent": 50.0,
                    "currentPercent": 66.6666,
                    "deviationPercent": 16.6666,
                    "deviationUSD": 2500.25
                  }
                },
                "actions": [
                  "SELL 0.125 XXBTZUSD"
                ],
                "drawdownPercent": 5.0,
                "fiatDeploymentPercent": 10.0,
                "effectiveUsdTargetPercent": 40.0
              }
            ]
        """;

        List<PortfolioSnapshot> parsed = mapper.readValue(legacyJson, new TypeReference<>() {});
        assertEquals(1, parsed.size());
        PortfolioSnapshot snapshot = parsed.get(0);

        assertEquals(0, snapshot.getTotalValueUSD().compareTo(new BigDecimal("15000.50")));
        assertEquals(0, snapshot.getDrawdownPercent().compareTo(new BigDecimal("5.0")));
        assertEquals(0, snapshot.getFiatDeploymentPercent().compareTo(new BigDecimal("10.0")));
        assertEquals(0, snapshot.getEffectiveUsdTargetPercent().compareTo(new BigDecimal("40.0")));
        assertEquals(1, snapshot.getActions().size());
        assertEquals("SELL 0.125 XXBTZUSD", snapshot.getActions().get(0));

        PortfolioSnapshot.AssetSnapshot btcAsset = snapshot.getAssets().get("XXBTZUSD");
        assertNotNull(btcAsset);
        assertEquals("XXBTZUSD", btcAsset.getSymbol());
        assertEquals(0, btcAsset.getBalance().compareTo(new BigDecimal("0.5")));
        assertEquals(0, btcAsset.getPrice().compareTo(new BigDecimal("20000.0")));
        assertEquals(0, btcAsset.getValueUSD().compareTo(new BigDecimal("10000.0")));
        assertEquals(0, btcAsset.getTargetPercent().compareTo(new BigDecimal("50.0")));
        assertEquals(0, btcAsset.getCurrentPercent().compareTo(new BigDecimal("66.6666")));
        assertEquals(0, btcAsset.getDeviationPercent().compareTo(new BigDecimal("16.6666")));
        assertEquals(0, btcAsset.getDeviationUSD().compareTo(new BigDecimal("2500.25")));
    }
}
