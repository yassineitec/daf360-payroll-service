package com.daf360.payroll.modules.simulation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SimulationRequest(
        @NotNull Long paysId,
        @NotNull @Positive BigDecimal inputNet,
        Long profileUserId,
        String contractType,      // CDI|CDD|STAGE|CIVP  (defaults to CDI)
        Integer joursTravailes    // working days used for FIXE_JOURNALIER rubriques (defaults to 22)
) {}
