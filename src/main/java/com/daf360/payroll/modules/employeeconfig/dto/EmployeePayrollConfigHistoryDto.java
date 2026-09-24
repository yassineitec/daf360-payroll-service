package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigHistoryDto(
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        String reason,
        Long changedBy,
        OffsetDateTime changedAt
) {}
