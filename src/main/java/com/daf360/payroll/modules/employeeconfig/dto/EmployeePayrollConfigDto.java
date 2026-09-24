package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EmployeePayrollConfigDto(
        Long profileUserId,
        Long paysId,
        String contractType,
        List<String> selectedBenefitCodes,
        BigDecimal currentGrossSalary,
        BigDecimal currentNetSalary,
        Long updatedBy,
        OffsetDateTime updatedAt
) {}
