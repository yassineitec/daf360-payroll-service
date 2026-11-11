package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationDto;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.simulation.dto.SimulationMode;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.daf360.payroll.modules.simulation.service.IndividualSimulationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * « Nouvelle rémunération » form: runs the payroll SIMULATION engine (the one behind
 * /payroll/simulator and the candidates' offer costing) on the salary being typed, and shows
 * how the benefits and the month's exceptional bonuses enter it. Nothing is saved — not in
 * the employee's configuration, not in the simulations history.
 *
 * <ul>
 *   <li>Benefits: the ticked ones, priced from the country's active rules. Taxable benefits
 *       raise the IRPP base; non-taxable ones are added to the net.</li>
 *   <li>Bonuses: the employee's recorded exceptional bonuses of the effective date's month,
 *       converted to the working currency and added to the gross of that month (so subject
 *       to social charges and IRPP). Shown as a second column, next to the regular month.</li>
 *   <li>Family situation: the employee's RH file (see IndividualSimulationService).</li>
 * </ul>
 */
@Service
public class RemunerationSimulationService {

    private final EmployeePayrollConfigService configService;
    private final IndividualSimulationService simulationService;
    private final EmployeePayrollBonusRepository bonusRepo;

    public RemunerationSimulationService(EmployeePayrollConfigService configService,
                                         IndividualSimulationService simulationService,
                                         EmployeePayrollBonusRepository bonusRepo) {
        this.configService = configService;
        this.simulationService = simulationService;
        this.bonusRepo = bonusRepo;
    }

    public RemunerationSimulationDto simulate(Long profileUserId, RemunerationSimulationRequest req) {
        Long paysId = req.paysId();
        String contract = req.contractType();
        if (paysId == null || contract == null || contract.isBlank()) {
            EmployeePayrollConfigDto cfg = configService.getOrDefault(profileUserId);
            if (paysId == null) paysId = cfg.paysId();
            if (contract == null || contract.isBlank()) contract = cfg.contractType();
        }
        if (paysId == null) {
            throw new IllegalArgumentException("Impossible de simuler : aucun pays configuré pour cet employé.");
        }
        contract = ContractTypeCodes.normalize(contract);
        List<String> codes = req.selectedBenefitCodes() != null ? req.selectedBenefitCodes() : List.of();
        BigDecimal base = configService.toWorkingCurrencyAmount(paysId, req.grossSalary(), req.inputCurrency());

        // Regular month: base salary + benefits.
        IndividualSimulationService.Computation monthly = simulationService.compute(request(paysId, profileUserId, contract, codes, base));
        String currency = monthly.fx().currency();

        // The month's exceptional bonuses, in the working currency.
        YearMonth period = req.effectiveDate() != null ? YearMonth.from(req.effectiveDate()) : YearMonth.now();
        List<RemunerationSimulationDto.Bonus> bonuses = new ArrayList<>();
        BigDecimal bonusTotal = BigDecimal.ZERO;
        for (EmployeePayrollBonus b : bonusRepo.findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(profileUserId)) {
            if (b.getPeriodYear() != period.getYear() || b.getPeriodMonth() != period.getMonthValue()) continue;
            BigDecimal converted = configService.toWorkingCurrencyAmount(paysId, b.getAmount(), b.getCurrency());
            bonuses.add(new RemunerationSimulationDto.Bonus(b.getId(), b.getLabel(), b.getAmount(), b.getCurrency(), converted));
            bonusTotal = bonusTotal.add(converted);
        }

        RemunerationSimulationDto.Breakdown withBonuses = null;
        if (bonusTotal.signum() > 0) {
            IndividualSimulationService.Computation month = simulationService.compute(
                    request(paysId, profileUserId, contract, codes, base.add(bonusTotal)));
            withBonuses = breakdown(month, base, bonusTotal);
        }

        List<RemunerationSimulationDto.Benefit> benefits = monthly.benefits().stream()
                .map(b -> new RemunerationSimulationDto.Benefit(b.getBenefitCode(), b.getBenefitLabelFr(),
                        b.getBenefitLabelEn(), b.getMonthlyValue(), b.getIsTaxable()))
                .toList();

        return new RemunerationSimulationDto(currency, contract, monthly.isMarried(), monthly.children(),
                period.getYear(), period.getMonthValue(), benefits, bonuses,
                breakdown(monthly, base, BigDecimal.ZERO), withBonuses);
    }

    /** BRUT_TO_NET on a working-currency gross; family left to the RH file (profileUserId). */
    private static SimulationRequest request(Long paysId, Long profileUserId, String contract,
                                             List<String> codes, BigDecimal gross) {
        return new SimulationRequest(paysId, null, profileUserId, contract, null, codes,
                null, null, null, null, null, null, SimulationMode.BRUT_TO_NET, gross, null);
    }

    private static RemunerationSimulationDto.Breakdown breakdown(IndividualSimulationService.Computation c,
                                                                 BigDecimal base, BigDecimal bonuses) {
        PayrollSimulatorService.PayrollResult r = c.result();
        BigDecimal taxable = BigDecimal.ZERO, nonTaxable = BigDecimal.ZERO;
        for (BenefitCatalogue b : c.benefits()) {
            BigDecimal v = b.getMonthlyValue() != null ? b.getMonthlyValue() : BigDecimal.ZERO;
            if (Boolean.TRUE.equals(b.getIsTaxable())) taxable = taxable.add(v);
            else if (Boolean.FALSE.equals(b.getIsTaxable())) nonTaxable = nonTaxable.add(v);
        }
        return new RemunerationSimulationDto.Breakdown(
                base, bonuses, r.gross(), taxable, nonTaxable,
                r.employeeCharges(), r.taxableBase(), r.irppAmount(), r.netInHand(),
                r.employerCharges(), r.loadedCost(), r.convergenceOk());
    }
}
