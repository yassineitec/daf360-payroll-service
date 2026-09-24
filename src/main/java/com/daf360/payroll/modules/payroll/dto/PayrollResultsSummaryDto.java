package com.daf360.payroll.modules.payroll.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Country-level totals of the latest calculated payroll period — feeds the KPI tiles on
 * `/payroll/engine-results`. Amounts are summed within one country only, so they share
 * {@code currencyCode}. When the country has no result yet, {@code periodYear} /
 * {@code periodMonth} / {@code lastCalculatedAt} are null and every total is zero.
 */
public record PayrollResultsSummaryDto(
        Long paysId,
        String currencyCode,
        Integer periodYear,
        Integer periodMonth,
        /** Employees with a result for the period (one result per employee and month). */
        int employeeCount,
        BigDecimal totalGross,
        /** Sum of strate 5 — net to pay, same figure as the per-employee "Net à payer". */
        BigDecimal totalNet,
        BigDecimal totalLoadedCost,
        int convergenceFailures,
        OffsetDateTime lastCalculatedAt
) {}
