package com.daf360.payroll.modules.simulation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Une ligne de la liste `/payroll/simulator` — l'essentiel d'une simulation individuelle,
 * sans les blobs JSON (rubriques, avantages) que seule la fiche résultat affiche.
 *
 * @param subjectLabel libellé du candidat, ou nom du collaborateur lu dans `users_ref`
 * @param netPaid      net versé réel (brut − charges salariales − IRPP), quel que soit le sens
 */
public record SimulationListItemDto(
        Long id,
        Long paysId,
        OffsetDateTime simulatedAt,
        Long profileUserId,
        String subjectLabel,
        String poste,
        String grade,
        String discipline,
        String contractType,
        String mode,
        BigDecimal gross,
        BigDecimal netPaid,
        BigDecimal loadedCost,
        BigDecimal costNetRatio,
        String localCurrency,
        Boolean convergenceOk,
        String simulatedByName
) {}
