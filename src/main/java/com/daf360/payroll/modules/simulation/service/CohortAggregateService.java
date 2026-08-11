package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient.HrEmployeeDto;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse;
import com.daf360.payroll.modules.simulation.dto.CohortFilterRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Cohort V2: fetches active employees from HR service, applies demographic filters,
 * runs the payroll engine per employee to compute current loadedCost, then applies
 * a salary modifier to project future cost. Returns only aggregate KPIs — no
 * individual salary data is exposed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CohortAggregateService {

    private final HrEmployeeClient   hrClient;
    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;
    private final FxSnapshotService  fxService;

    public CohortAggregateResponse aggregate(CohortFilterRequest req) {
        // Load engine parameters once for this paysId
        ParameterSet ps = paramSetService.loadActiveEntity(req.paysId());
        List<SocialChargeRate> rates     = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> benefits  = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique>  rubriques = paramSetService.loadRubriques(ps.getId());

        // Fetch active employees from HR service
        List<HrEmployeeDto> employees = hrClient.fetchActiveEmployees(req.paysId());

        // Apply demographic filters
        List<HrEmployeeDto> filtered = employees.stream()
                .filter(e -> req.grade()        == null || req.grade().equalsIgnoreCase(e.grade()))
                .filter(e -> req.discipline()   == null || req.discipline().equalsIgnoreCase(e.discipline()))
                .filter(e -> req.contractType() == null || req.contractType().equalsIgnoreCase(e.contractType()))
                .toList();

        if (filtered.isEmpty()) {
            String currency = fxService.localCurrency(req.paysId());
            return emptyResponse(req, currency);
        }

        String modType = req.modifierType() != null ? req.modifierType().toUpperCase() : "PCT";
        BigDecimal modVal = req.modifierValue() != null ? req.modifierValue() : BigDecimal.ZERO;

        BigDecimal currentTotal    = BigDecimal.ZERO;
        BigDecimal projectedTotal  = BigDecimal.ZERO;
        int successCount = 0;

        for (HrEmployeeDto emp : filtered) {
            try {
                String contractType = emp.contractType() != null ? emp.contractType() : "CDI";
                PayrollSimulatorService.PayrollResult result =
                        simulatorService.computeFromNet(emp.salaireNetRh(), ps, rates, benefits, rubriques, contractType, 22);

                BigDecimal currentCost = result.loadedCost();

                BigDecimal projectedNet = applyModifier(emp.salaireNetRh(), modType, modVal);
                PayrollSimulatorService.PayrollResult projectedResult =
                        simulatorService.computeFromNet(projectedNet, ps, rates, benefits, rubriques, contractType, 22);
                BigDecimal projectedCost = projectedResult.loadedCost();

                currentTotal   = currentTotal.add(currentCost);
                projectedTotal = projectedTotal.add(projectedCost);
                successCount++;
            } catch (Exception ex) {
                log.warn("Skipping employee userId={} in cohort aggregate: {}", emp.userId(), ex.getMessage());
            }
        }

        BigDecimal deltaMonthly = projectedTotal.subtract(currentTotal).setScale(4, RoundingMode.HALF_UP);
        BigDecimal deltaAnnual  = deltaMonthly.multiply(BigDecimal.valueOf(12)).setScale(4, RoundingMode.HALF_UP);

        String currency = fxService.localCurrency(req.paysId());

        return new CohortAggregateResponse(
                successCount,
                currentTotal.setScale(4, RoundingMode.HALF_UP),
                projectedTotal.setScale(4, RoundingMode.HALF_UP),
                deltaMonthly,
                deltaAnnual,
                fxService.convertToEur(currentTotal,   req.paysId()),
                fxService.convertToEur(projectedTotal, req.paysId()),
                fxService.convertToChf(currentTotal,   req.paysId()),
                fxService.convertToChf(projectedTotal, req.paysId()),
                currency,
                modType,
                modVal,
                req
        );
    }

    private BigDecimal applyModifier(BigDecimal base, String modType, BigDecimal modVal) {
        return switch (modType) {
            case "ABSOLU" -> base.add(modVal).max(BigDecimal.ZERO);
            default -> {   // PCT
                BigDecimal factor = BigDecimal.ONE.add(modVal.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                yield base.multiply(factor).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
            }
        };
    }

    private CohortAggregateResponse emptyResponse(CohortFilterRequest req, String currency) {
        return new CohortAggregateResponse(
                0,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                currency,
                req.modifierType() != null ? req.modifierType().toUpperCase() : "PCT",
                req.modifierValue() != null ? req.modifierValue() : BigDecimal.ZERO,
                req
        );
    }
}
