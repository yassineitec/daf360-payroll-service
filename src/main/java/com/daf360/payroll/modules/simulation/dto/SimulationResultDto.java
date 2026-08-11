package com.daf360.payroll.modules.simulation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 5-strata mapping:
 *   S1 = inputNet       (net versé — cible saisie)
 *   S2 = netTaxable     (net imposable = base IRPP = gross - cotisations salariales)
 *   S3 = gross          (brut base taxable)
 *   S4 = grossWithBenefits (brut total = S3 + avantages exonérés)
 *   S5 = loadedCost     (coût chargé total = S4 + charges patronales)
 */
public record SimulationResultDto(
        Long id,
        Long paysId,
        Long profileUserId,
        Long parameterSetId,
        String simulationType,
        String contractType,
        // 5 strates
        BigDecimal inputNet,          // S1 net versé
        BigDecimal netTaxable,        // S2 net imposable (IRPP base)
        BigDecimal taxableBase,       // S2 alias (kept for compatibility)
        BigDecimal gross,             // S3 brut base taxable
        BigDecimal grossWithBenefits, // S4 brut total (S3 + avantages exonérés)
        BigDecimal loadedCost,        // S5 coût chargé total
        // multi-currency (applied to S5)
        BigDecimal loadedCostEur,
        BigDecimal loadedCostUsd,
        BigDecimal loadedCostChf,
        BigDecimal fxRateEur,
        BigDecimal fxRateUsd,
        BigDecimal fxRateChf,
        String localCurrency,
        // ratios and details
        BigDecimal costNetRatio,      // loadedCost / inputNet
        BigDecimal irppAmount,
        BigDecimal employeeCharges,
        BigDecimal employerCharges,
        // applied components (JSON)
        String benefitsApplied,
        String rubriquesApplied,
        // convergence
        Integer iterationsUsed,
        Boolean convergenceOk,
        // grouping
        Long cohortId,
        // candidate metadata (for PDF)
        String candidateLabel,
        String poste,
        String grade,
        String discipline,
        // simulation direction (NET_TO_BRUT | BRUT_TO_NET)
        String mode,
        OffsetDateTime simulatedAt
) {}
