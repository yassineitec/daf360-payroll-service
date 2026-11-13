package com.daf360.payroll.modules.salaryadvance.entity;

/**
 * The debt contract of an approved advance (V39, {@code CK_sa_contract_status}).
 * NULL on the row until the advance is approved.
 */
public enum ContractStatus {
    /** Generated at approval, waiting at the RH office for signature and legalisation. */
    TO_SIGN,
    /** The signed, legalised copy is archived in the employee's RH folder. */
    SIGNED
}
