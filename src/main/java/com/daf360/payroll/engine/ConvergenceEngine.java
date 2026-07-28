package com.daf360.payroll.engine;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.function.Function;

/**
 * Newton-Raphson solver with bisection fallback.
 *
 * Finds x such that f(x) = 0 within the given tolerance.
 * Used to invert the gross→net function: given a desired net, finds gross.
 *
 * Strategy:
 *   1. Newton-Raphson up to maxIterations.
 *   2. If Newton-Raphson oscillates or fails, fall back to bisection in [lo, hi].
 *   3. If still no convergence after maxIterations, throws ConvergenceException.
 */
@Service
public class ConvergenceEngine {

    private static final int DERIVATIVE_POINTS = 2;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal H = new BigDecimal("0.01");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /**
     * @param f           residual function: f(gross) = net(gross) - targetNet
     * @param x0          initial guess
     * @param lo          bisection lower bound
     * @param hi          bisection upper bound
     * @param tolerance   convergence tolerance (from ParameterSet)
     * @param maxIter     max iterations (from ParameterSet, typically 50)
     * @return            converged gross value and iteration count
     */
    public Result solve(Function<BigDecimal, BigDecimal> f,
                        BigDecimal x0, BigDecimal lo, BigDecimal hi,
                        BigDecimal tolerance, int maxIter) {

        BigDecimal x = x0;
        BigDecimal residual = null;

        for (int i = 0; i < maxIter; i++) {
            residual = f.apply(x);

            if (residual.abs().compareTo(tolerance) <= 0) {
                return new Result(x, residual, i + 1, true);
            }

            BigDecimal fLo = f.apply(lo);
            BigDecimal fHi = f.apply(hi);

            // Switch to bisection if Newton-Raphson derivative is near zero
            // or if x left the bracket (prevents divergence)
            if (x.compareTo(lo) < 0 || x.compareTo(hi) > 0
                    || fLo.signum() == fHi.signum()) {
                return bisection(f, lo, hi, tolerance, maxIter - i, i, residual);
            }

            BigDecimal derivative = derivative(f, x);
            if (derivative.compareTo(new BigDecimal("1e-10")) < 0) {
                return bisection(f, lo, hi, tolerance, maxIter - i, i, residual);
            }

            x = x.subtract(residual.divide(derivative, MC));
        }

        throw new ConvergenceException(x, residual, maxIter);
    }

    private Result bisection(Function<BigDecimal, BigDecimal> f,
                             BigDecimal lo, BigDecimal hi,
                             BigDecimal tolerance, int remainingIter,
                             int usedIter, BigDecimal lastResidual) {
        BigDecimal a = lo;
        BigDecimal b = hi;
        BigDecimal mid = null;
        BigDecimal residual = lastResidual;

        for (int i = 0; i < remainingIter; i++) {
            mid = a.add(b).divide(TWO, MC);
            residual = f.apply(mid);

            if (residual.abs().compareTo(tolerance) <= 0) {
                return new Result(mid, residual, usedIter + i + 1, true);
            }

            if (f.apply(a).signum() == residual.signum()) {
                a = mid;
            } else {
                b = mid;
            }
        }

        throw new ConvergenceException(mid, residual, usedIter + remainingIter);
    }

    private BigDecimal derivative(Function<BigDecimal, BigDecimal> f, BigDecimal x) {
        BigDecimal fPlus  = f.apply(x.add(H));
        BigDecimal fMinus = f.apply(x.subtract(H));
        return fPlus.subtract(fMinus).divide(H.multiply(TWO), MC).abs();
    }

    public record Result(BigDecimal value, BigDecimal residual, int iterations, boolean converged) {}
}
