package com.daf360.payroll.modules.calibration.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CalibrationCycleDto(
        Long id,
        Long paysId,
        String period,
        Long parameterSetId,
        String status,
        BigDecimal predictedTotalLoadedCost,
        BigDecimal actualTotalLoadedCost,
        BigDecimal variancePct,
        Integer headcount,
        OffsetDateTime closedAt,
        String notes,
        OffsetDateTime createdAt
) {}
