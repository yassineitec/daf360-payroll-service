package com.daf360.payroll.engine;

import java.math.BigDecimal;

public class ConvergenceException extends RuntimeException {

    private final BigDecimal lastEstimate;
    private final BigDecimal lastResidual;
    private final int iterations;

    public ConvergenceException(BigDecimal lastEstimate, BigDecimal lastResidual, int iterations) {
        super(String.format(
            "Newton-Raphson did not converge after %d iterations. Last estimate: %s, residual: %s",
            iterations, lastEstimate, lastResidual));
        this.lastEstimate = lastEstimate;
        this.lastResidual = lastResidual;
        this.iterations = iterations;
    }

    public BigDecimal getLastEstimate() { return lastEstimate; }
    public BigDecimal getLastResidual() { return lastResidual; }
    public int getIterations() { return iterations; }
}
