package com.daf360.payroll.modules.payroll.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Request body to trigger a payroll run for a single employee.
 */
@Getter
@Setter
public class RunPayrollRequest {

    /** Employee identifier from the RH service. */
    @NotNull
    private Long employeeId;

    /** Country/entity identifier used to resolve the active PayrollParamSet. */
    @NotNull
    private Long paysId;

    /** Calendar year of the pay period (e.g. 2025). */
    @NotNull
    private Integer periodYear;

    /** Calendar month of the pay period (1 = January, 12 = December). */
    @NotNull
    @Min(1)
    @Max(12)
    private Integer periodMonth;

    /**
     * Contract type code used to filter rubriques with contractTypeFilter set.
     * Defaults to "CDI" when null.
     */
    private String contractTypeCode;

    /**
     * Number of working days in this period, used for pro-rata calculations.
     * Defaults to 22 when null.
     */
    private BigDecimal joursOuvresMois;

    /** Username or system identifier that initiated the run (audit trail). */
    private String triggeredBy;
}
