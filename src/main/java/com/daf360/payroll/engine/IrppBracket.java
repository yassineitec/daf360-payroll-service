package com.daf360.payroll.engine;

import java.math.BigDecimal;

/**
 * A single IRPP tax bracket: income in [lowerBound, upperBound) taxed at rate.
 * upperBound null means the bracket is open-ended (top bracket).
 */
public record IrppBracket(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal rate) {

    public boolean contains(BigDecimal amount) {
        if (amount.compareTo(lowerBound) < 0) return false;
        return upperBound == null || amount.compareTo(upperBound) < 0;
    }
}
