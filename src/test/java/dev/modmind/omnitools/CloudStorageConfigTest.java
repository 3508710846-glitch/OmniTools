package dev.modmind.omnitools;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudStorageConfigTest {
    @Test
    void defaultExpansionAllowsTwentyTotalPagesAtTheTenPercentCurve() {
        CloudStorageConfig config = CloudStorageConfig.defaultConfig();

        assertEquals(20, config.maxPages());
        assertEquals(100L, config.expansionCostForPage(2));
        assertEquals(110L, config.expansionCostForPage(3));
        assertEquals(215L, config.expansionCostForPage(10));
        assertEquals(556L, config.expansionCostForPage(20));
    }

    @Test
    void priceIsCalculatedFromTheBaseValueThenRoundedOnce() {
        BigDecimal multiplier = new BigDecimal("1.125");

        // Page four is deliberately used because repeatedly multiplying a rounded page-three
        // price would yield 128, while the specified base-price calculation yields 127.
        assertEquals(127L, CloudStorageConfig.calculateExpansionCost(100L, multiplier,
                CloudStorageConfig.ExpansionRoundingMode.CEILING, 4));
        assertEquals(112L, CloudStorageConfig.calculateExpansionCost(100L, multiplier,
                CloudStorageConfig.ExpansionRoundingMode.FLOOR, 3));
        assertEquals(113L, CloudStorageConfig.calculateExpansionCost(100L, multiplier,
                CloudStorageConfig.ExpansionRoundingMode.HALF_UP, 3));
    }

    @Test
    void zeroBasePriceStillChargesAtLeastOneCurrency() {
        assertEquals(1L, CloudStorageConfig.calculateExpansionCost(0L, new BigDecimal("1.1"),
                CloudStorageConfig.ExpansionRoundingMode.CEILING, 2));
    }

    @Test
    void priceRequestsOutsideThePurchasablePageRangeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CloudStorageConfig.calculateExpansionCost(100L,
                new BigDecimal("1.1"), CloudStorageConfig.ExpansionRoundingMode.CEILING, 1));
        assertThrows(IllegalArgumentException.class, () -> CloudStorageConfig.calculateExpansionCost(100L,
                new BigDecimal("1.1"), CloudStorageConfig.ExpansionRoundingMode.CEILING, 21));
    }
}
