package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record EmployeePayrollBonusDto(
        Long id,
        Long profileUserId,
        BigDecimal amount,
        String currency,
        Integer periodMonth,
        Integer periodYear,
        String label,
        String comment,
        Long createdBy,
        OffsetDateTime createdAt
) {}
