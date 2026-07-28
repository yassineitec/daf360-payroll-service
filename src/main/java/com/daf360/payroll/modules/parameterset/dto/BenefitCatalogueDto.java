package com.daf360.payroll.modules.parameterset.dto;

import java.math.BigDecimal;

public record BenefitCatalogueDto(
        Long id,
        String benefitCode,
        String benefitLabelFr,
        String benefitLabelEn,
        String valuationMethod,
        BigDecimal monthlyValue,
        BigDecimal employeeShare,
        BigDecimal employerShare,
        Boolean isTaxable
) {}
