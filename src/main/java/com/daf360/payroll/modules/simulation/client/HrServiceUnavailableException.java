package com.daf360.payroll.modules.simulation.client;

/** The HR service could not be reached or refused the internal key — a 503, never "0 employees". */
public class HrServiceUnavailableException extends RuntimeException {
    public HrServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
