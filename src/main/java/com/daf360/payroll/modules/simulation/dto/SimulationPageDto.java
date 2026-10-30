package com.daf360.payroll.modules.simulation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Page de la liste `/payroll/simulator` + les chiffres du bandeau KPI, calculés sur tout le
 * périmètre filtré (pas seulement la page affichée).
 */
public record SimulationPageDto(
        List<SimulationListItemDto> content,
        long totalElements,
        int totalPages,
        int page,
        int size,
        Summary summary
) {
    /**
     * @param monthCount        simulations du mois en cours
     * @param prevMonthCount    simulations du mois précédent (pour la tendance)
     * @param partialCount      simulations en convergence partielle — résultats estimés
     * @param prevMonthAvgRatio ratio coût / net moyen du mois précédent, null s'il n'y en a pas
     */
    public record Summary(
            long total,
            long employees,
            long candidates,
            long monthCount,
            long prevMonthCount,
            long partialCount,
            BigDecimal avgLoadedCost,
            BigDecimal minLoadedCost,
            BigDecimal maxLoadedCost,
            BigDecimal avgRatio,
            BigDecimal prevMonthAvgRatio
    ) {}
}
