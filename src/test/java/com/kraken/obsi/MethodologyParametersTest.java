package com.kraken.obsi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MethodologyParametersTest {

    @Test
    void defaultsMatchSection7() {
        MethodologyParameters p = MethodologyParameters.DEFAULT;

        assertEquals(0, new BigDecimal("0.05").compareTo(p.pedLimit()), "PED limit 5%");
        assertEquals(0, new BigDecimal("0.005").compareTo(p.midSpreadCutoff()), "mid-spread cutoff D = 0.5%");
        assertEquals(0, BigDecimal.ONE.compareTo(p.spacing()), "curve spacing s = 1 XYZ");
        assertEquals(0, new BigDecimal("0.3").compareTo(p.lambdaDepthFactor()), "lambda = 1 / (0.3 v_T)");
        assertEquals(2, p.publishedScale(), "two decimal places");
        assertEquals(RoundingMode.HALF_UP, p.publishedRounding(), "Java HALF_UP");
    }

    @Test
    void rejectsNonPositiveOrMissingParameters() {
        BigDecimal one = BigDecimal.ONE;
        assertThrows(IllegalArgumentException.class,
                () -> new MethodologyParameters(BigDecimal.ZERO, one, one, one, 2, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodologyParameters(one, new BigDecimal("-0.005"), one, one, 2, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodologyParameters(one, one, BigDecimal.ZERO, one, 2, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodologyParameters(one, one, one, BigDecimal.ZERO, 2, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodologyParameters(one, one, one, one, -1, RoundingMode.HALF_UP));
        assertThrows(NullPointerException.class,
                () -> new MethodologyParameters(one, one, one, one, 2, null));
    }
}
