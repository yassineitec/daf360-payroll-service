package com.daf360.payroll.modules.ref.service;

/** Thrown when a simulator request names a currency payroll_fx_rates has no cached rate for
 *  (sync hasn't run yet, or finance has nothing configured for it). Never silently falls back
 *  to a 1:1 rate -- a wrong silent rate is exactly what this feature exists to prevent. */
public class FxEntryConversionException extends RuntimeException {
    public FxEntryConversionException(String message) {
        super(message);
    }
}
