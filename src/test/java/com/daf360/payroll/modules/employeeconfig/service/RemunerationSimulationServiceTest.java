package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationDto;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollBonus;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollBonusRepository;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.simulation.dto.SimulationMode;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.daf360.payroll.modules.simulation.service.IndividualSimulationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemunerationSimulationServiceTest {

    @Mock EmployeePayrollConfigService configService;
    @Mock IndividualSimulationService simulationService;
    @Mock EmployeePayrollBonusRepository bonusRepo;

    private static final FxSnapshotService.FxSnapshot TND =
            new FxSnapshotService.FxSnapshot("TND", new BigDecimal("0.30"), null, null);

    private RemunerationSimulationService service() {
        return new RemunerationSimulationService(configService, simulationService, bonusRepo);
    }

    /** The engine echoes the gross it was given, with a net of 80 %. */
    private void engine(BenefitCatalogue... benefits) {
        when(simulationService.compute(any())).thenAnswer(inv -> {
            SimulationRequest r = inv.getArgument(0);
            BigDecimal g = r.inputGross();
            PayrollSimulatorService.PayrollResult res = new PayrollSimulatorService.PayrollResult(
                    g.multiply(new BigDecimal("0.8")), g, g, g, g.multiply(new BigDecimal("1.2")),
                    BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    3, true, List.of());
            return new IndividualSimulationService.Computation(res, null, List.of(benefits), TND, "CDI",
                    SimulationMode.BRUT_TO_NET, false, null, g, null, null, true, 2);
        });
    }

    private static EmployeePayrollBonus bonus(long id, String amount, String currency, int month, int year) {
        EmployeePayrollBonus b = new EmployeePayrollBonus();
        b.setId(id);
        b.setAmount(new BigDecimal(amount));
        b.setCurrency(currency);
        b.setPeriodMonth(month);
        b.setPeriodYear(year);
        b.setLabel("Prime " + id);
        return b;
    }

    private static RemunerationSimulationRequest req(String gross, List<String> codes) {
        return new RemunerationSimulationRequest(new BigDecimal(gross), null, 179L, "CDI", codes, LocalDate.of(2026, 12, 15));
    }

    @Test
    void noBonusThatMonth_onlyTheRegularMonth_andNothingSaved() {
        when(configService.toWorkingCurrencyAmount(eq(179L), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(bonusRepo.findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(10L))
                .thenReturn(List.of(bonus(1, "500", "TND", 11, 2026)));   // another month
        engine();

        RemunerationSimulationDto dto = service().simulate(10L, req("3000", List.of()));

        assertEquals("TND", dto.currency());
        assertEquals(0, new BigDecimal("3000").compareTo(dto.monthly().gross()));
        assertNull(dto.withBonuses());
        assertEquals(0, dto.bonuses().size());
        verify(simulationService, times(1)).compute(any());
        verify(simulationService, org.mockito.Mockito.never()).simulate(any(), any());
    }

    @Test
    void bonusesOfTheMonth_convertedAndAddedToTheGross_ofASecondRun() {
        when(configService.toWorkingCurrencyAmount(eq(179L), any(), any())).thenAnswer(inv -> {
            BigDecimal a = inv.getArgument(1);
            String c = inv.getArgument(2);
            return "EUR".equals(c) ? a.multiply(new BigDecimal("3")) : a;   // 1 EUR = 3 TND
        });
        when(bonusRepo.findByProfileUserIdOrderByPeriodYearDescPeriodMonthDesc(10L)).thenReturn(List.of(
                bonus(1, "200", "TND", 12, 2026),
                bonus(2, "100", "EUR", 12, 2026),
                bonus(3, "999", "TND", 1, 2027)));
        BigDecimal mealValue = new BigDecimal("198");
        BenefitCatalogue meal = new BenefitCatalogue();
        meal.setBenefitCode("MEAL");
        meal.setMonthlyValue(mealValue);
        meal.setIsTaxable(false);
        engine(meal);

        RemunerationSimulationDto dto = service().simulate(10L, req("3000", List.of("MEAL")));

        ArgumentCaptor<SimulationRequest> runs = ArgumentCaptor.forClass(SimulationRequest.class);
        verify(simulationService, times(2)).compute(runs.capture());
        assertEquals(0, new BigDecimal("3000").compareTo(runs.getAllValues().get(0).inputGross()));
        assertEquals(0, new BigDecimal("3500").compareTo(runs.getAllValues().get(1).inputGross()));   // 3000 + 200 + 100×3
        assertEquals(List.of("MEAL"), runs.getAllValues().get(0).selectedBenefitCodes());
        assertEquals(10L, runs.getAllValues().get(0).profileUserId());   // family from the RH file

        assertEquals(2, dto.bonuses().size());
        assertEquals(0, new BigDecimal("500").compareTo(dto.withBonuses().bonuses()));
        assertEquals(0, new BigDecimal("3000").compareTo(dto.withBonuses().baseSalary()));
        assertEquals(0, mealValue.compareTo(dto.monthly().nonTaxableBenefits()));
        assertEquals(true, dto.married());
        assertEquals(2, dto.children());
    }
}
