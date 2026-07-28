package com.daf360.payroll.modules.calibration.dto;

import java.math.BigDecimal;

public record PartnerPayrollRow(
        Long profileUserId,
        BigDecimal actualLoadedCost,
        String contractType,
        Integer sourceLine
) {}
