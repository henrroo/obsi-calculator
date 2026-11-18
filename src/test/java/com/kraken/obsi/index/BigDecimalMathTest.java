package com.kraken.obsi.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BigDecimalMathTest {

    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);

    /** e to 50 significant digits (OEIS A001113). */
    private static final BigDecimal E = new BigDecimal("2.7182818284590452353602874713526624977572470937");

    @Test
    void expOfZeroIsOne() {
        assertEquals(0, BigDecimal.ONE.compareTo(BigDecimalMath.exp(BigDecimal.ZERO, MC)));
    }

    @Test
    void expOfOneIsEToFullPrecision() {
        assertEquals(E.round(MC), BigDecimalMath.exp(BigDecimal.ONE, MC));
    }

    @Test
    void expOfMinusOneIsTheReciprocalOfE() {
        // 1/e = 0.36787944117144232159552377016146086744581113103176...
        assertEquals(new BigDecimal("0.3678794411714423215955237701614608674458"), BigDecimalMath.exp(BigDecimal.ONE.negate(), MC));
    }

    @Test
    void expOfMinusTenThirdsMatchesTheReferenceValue() {
        // The smallest weight exponent the methodology can produce: -lambda * v_T = -1/0.3.
        // e^(-10/3) = 0.035673993347252397604438480239729786232314... (Python decimal, 50 digits)
        BigDecimal x = BigDecimal.TEN.divide(new BigDecimal("3"), MC).negate();

        BigDecimal actual = BigDecimalMath.exp(x, MC);

        assertEquals(new BigDecimal("0.03567399334725239760444"), actual.round(new MathContext(22)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-3.3333", "-1.2", "-0.8333333", "-0.001", "0.5", "1", "2.75", "7.5", "20"})
    void agreesWithMathExpToDoublePrecision(String value) {
        double expected = Math.exp(Double.parseDouble(value));

        double actual = BigDecimalMath.exp(new BigDecimal(value), MC).doubleValue();

        assertEquals(expected, actual, Math.abs(expected) * 1e-15);
    }

    @Test
    void expOfXTimesExpOfMinusXIsOne() {
        BigDecimal x = new BigDecimal("2.3456789");

        BigDecimal product = BigDecimalMath.exp(x, MC).multiply(BigDecimalMath.exp(x.negate(), MC), MC);

        assertTrue(product.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("1e-38")) < 0, product.toString());
    }

    @Test
    void resultHasTheRequestedPrecision() {
        assertEquals(40, BigDecimalMath.exp(new BigDecimal("0.7"), MC).precision());
        assertEquals(10, BigDecimalMath.exp(new BigDecimal("0.7"), new MathContext(10)).precision());
    }

    @Test
    void rejectsUnlimitedPrecision() {
        assertThrows(IllegalArgumentException.class, () -> BigDecimalMath.exp(BigDecimal.ONE, MathContext.UNLIMITED));
    }
}
