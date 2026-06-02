package com.picsou.service;

import com.picsou.service.HoldingDedup.HoldingAgg;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingDedupTest {

    @Test
    void vwapMerge_computesWeightedAverage() {
        // (qty=2, avg=10) + (qty=3, avg=20) → VWAP = (2*10 + 3*20) / 5 = 80/5 = 16
        HoldingAgg a = new HoldingAgg(bd("2"), bd("10"), bd("100"), "Foo");
        HoldingAgg b = new HoldingAgg(bd("3"), bd("20"), bd("110"), "Foo");

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.quantity()).isEqualByComparingTo("5");
        assertThat(merged.averageBuyIn()).isEqualByComparingTo("16.00000000");
    }

    @Test
    void vwapMerge_handlesNonIntegerVwap() {
        // (qty=10, avg=100) + (qty=20, avg=200) → VWAP = 5000/30 = 166.66666667
        HoldingAgg a = new HoldingAgg(bd("10"), bd("100"), null, null);
        HoldingAgg b = new HoldingAgg(bd("20"), bd("200"), null, null);

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.quantity()).isEqualByComparingTo("30");
        assertThat(merged.averageBuyIn()).isEqualByComparingTo("166.66666667");
    }

    @Test
    void vwapMerge_isOrderIndependent() {
        // VWAP must be symmetric: merge(a,b) == merge(b,a) for the average
        HoldingAgg a = new HoldingAgg(bd("2"), bd("10"), bd("100"), "A");
        HoldingAgg b = new HoldingAgg(bd("3"), bd("20"), bd("110"), "B");

        BigDecimal avgAB = HoldingDedup.vwapMerge(a, b).averageBuyIn();
        BigDecimal avgBA = HoldingDedup.vwapMerge(b, a).averageBuyIn();

        assertThat(avgAB).isEqualByComparingTo(avgBA);
    }

    @Test
    void vwapMerge_treatsNullAverageAsZero() {
        // (qty=2, avg=null) + (qty=3, avg=20) → VWAP = (0 + 60) / 5 = 12
        HoldingAgg a = new HoldingAgg(bd("2"), null, null, null);
        HoldingAgg b = new HoldingAgg(bd("3"), bd("20"), null, null);

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged.averageBuyIn()).isEqualByComparingTo("12.00000000");
    }

    @Test
    void vwapMerge_returnsPrevWhenTotalQuantityZero() {
        // Cannot divide by zero — guard returns prev unchanged
        HoldingAgg a = new HoldingAgg(BigDecimal.ZERO, bd("10"), bd("100"), "A");
        HoldingAgg b = new HoldingAgg(BigDecimal.ZERO, bd("20"), bd("110"), "B");

        HoldingAgg merged = HoldingDedup.vwapMerge(a, b);

        assertThat(merged).isSameAs(a);
    }

    @Test
    void vwapMerge_keepsFirstNonNullName() {
        HoldingAgg withName = new HoldingAgg(bd("1"), bd("1"), null, "FromIsinA");
        HoldingAgg withoutName = new HoldingAgg(bd("1"), bd("1"), null, null);

        assertThat(HoldingDedup.vwapMerge(withName, withoutName).name()).isEqualTo("FromIsinA");
        assertThat(HoldingDedup.vwapMerge(withoutName, withName).name()).isEqualTo("FromIsinA");
    }

    @Test
    void vwapMerge_fallsBackToNextCurrentPriceWhenPrevIsNull() {
        HoldingAgg a = new HoldingAgg(bd("1"), bd("1"), null, "A");
        HoldingAgg b = new HoldingAgg(bd("1"), bd("1"), bd("42"), "B");

        assertThat(HoldingDedup.vwapMerge(a, b).currentPrice()).isEqualByComparingTo("42");
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
