package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.payroll.calculator.*;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.dto.RunPayrollResponse;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollRubriqueDefRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Acceptance test for the new-model engine fix: running it with the same inputs as the
 * old model's already-validated Tunisia reference scenario (gross=462, CDI, single, 0
 * children, no benefits) must produce the SAME net (415.40) as the old model's reference
 * — but a DIFFERENT, correctly higher, loadedCost (557.03, not the old model's 543.17),
 * since the new model correctly includes TFP/FOPROLOS employer charges the old model has
 * no concept of at all.
 */
@ExtendWith(MockitoExtension.class)
class PayrollOrchestratorTunisiaEngineFixTest {

    @Mock private PayrollCountryRepository countryRepo;
    @Mock private PayrollParamSetRepository paramSetRepo;
    @Mock private PayrollRubriqueDefRepository rubriqueRepo;
    @Mock private PayrollResultRepository resultRepo;
    @Mock private EmployeePayrollConfigRepository employeeConfigRepo;
    @Mock private ParameterSetService parameterSetService;
    @Mock private ForexSnapshotService forexService;

    private static final String TUNISIA_PARAMETERS = "{"
        + "\"salaire_base_tn\":2500.0,\"taux_cnss_sal_tn\":9.68,\"taux_cnss_empl_tn\":16.57,"
        + "\"plafond_cnss_mens_tn\":6570.0,\"taux_tfp_tn\":2.0,\"taux_foprolos_tn\":1.0,"
        + "\"taux_at_tn\":1.0,\"taux_css_tn\":0.5,\"seuil_css_annuel_tn\":6000.0,"
        + "\"abattement_rate_tn\":0.10,\"abattement_cap_tn\":2000.0,\"family_married_tn\":300.0,"
        + "\"family_per_child_tn\":100.0,\"family_max_children_tn\":4,"
        + "\"bareme_irpp_tn\":[{\"min\":0,\"max\":5000,\"rate\":0},"
        + "{\"min\":5000,\"max\":10000,\"rate\":15},{\"min\":10000,\"max\":20000,\"rate\":25},"
        + "{\"min\":20000,\"max\":30000,\"rate\":30},{\"min\":30000,\"max\":40000,\"rate\":33},"
        + "{\"min\":40000,\"max\":50000,\"rate\":36},{\"min\":50000,\"max\":70000,\"rate\":38},"
        + "{\"min\":70000,\"max\":null,\"rate\":40}]}";

    private static PayrollRubriqueDef rubrique(String code, int strate, String nature, String mode,
                                                String assietteCode, String paramKeyTaux,
                                                String paramKeyPlafond, String paramKeyBareme,
                                                String formula, String payer, int order) {
        PayrollRubriqueDef r = new PayrollRubriqueDef();
        r.setCode(code);
        r.setLabelFr(code);
        r.setStrate(strate);
        r.setNature(nature);
        r.setModeCalcul(mode);
        r.setAssietteCode(assietteCode);
        r.setParamKeyTaux(paramKeyTaux);
        r.setParamKeyPlafond(paramKeyPlafond);
        r.setParamKeyBareme(paramKeyBareme);
        r.setFormulaExpression(formula);
        r.setPeriodicite("MENSUEL");
        r.setDisplayOrder(order);
        r.setActive(true);
        r.setPayer(payer);
        return r;
    }

    private List<PayrollRubriqueDef> tunisiaRubriques() {
        return List.of(
            rubrique("SALAIRE_BASE", 1, "GAIN", "MONTANT_FIXE", null, "salaire_base_tn", null, null, null, null, 10),
            rubrique("CNSS_SALARIE", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_cnss_sal_tn", "plafond_cnss_mens_tn", null, null, "EMPLOYEE", 20),
            rubrique("CNSS_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_cnss_empl_tn", "plafond_cnss_mens_tn", null, null, "EMPLOYER", 25),
            rubrique("TFP", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_tfp_tn", null, null, null, "EMPLOYER", 30),
            rubrique("FOPROLOS", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_foprolos_tn", null, null, null, "EMPLOYER", 35),
            rubrique("AT_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_at_tn", null, null, null, "EMPLOYER", 40),
            rubrique("ASSIETTE_IRPP", 4, "TAXE", "FORMULE", null, null, null, null,
                "T(Math).ceil(T(Math).max(((BRUT + AVANTAGES - CNSS_SALARIE) * 12) - T(Math).min(((BRUT + AVANTAGES - CNSS_SALARIE) * 12) * abattement_rate_tn, abattement_cap_tn) - ((IS_MARRIED * family_married_tn) + (T(Math).min(CHILDREN_COUNT, family_max_children_tn) * family_per_child_tn)), 0.0)) / 12",
                null, 45),
            rubrique("IRPP", 5, "TAXE", "ANNUALISE_BAREME", "STRATE_4", null, null, "bareme_irpp_tn", null, null, 50),
            rubrique("CSS", 5, "TAXE", "TAUX_PCT", "STRATE_4", "taux_css_tn", null, null, null, null, 60)
        );
    }

    @Test
    void tunisiaGross462_singleNoChildren_matchesOldModelReference() {
        PayrollCountry country = new PayrollCountry();
        country.setId(7L);
        country.setPaysId(179L);
        when(countryRepo.findByPaysIdAndActiveTrue(179L)).thenReturn(Optional.of(country));

        PayrollParamSet paramSet = new PayrollParamSet();
        paramSet.setId(7L);
        paramSet.setCountryId(7L);
        paramSet.setStatus("ACTIVE");
        paramSet.setVersionNumber(1);
        paramSet.setEffectiveDate(LocalDate.of(2026, 1, 1));
        paramSet.setParameters(TUNISIA_PARAMETERS);
        when(paramSetRepo.findByCountryIdAndStatus(7L, "ACTIVE")).thenReturn(Optional.of(paramSet));

        when(rubriqueRepo.findByCountryIdAndActiveTrue(7L)).thenReturn(tunisiaRubriques());

        EmployeePayrollConfig config = new EmployeePayrollConfig();
        config.setCurrentGrossSalary(new BigDecimal("462"));
        config.setIsMarried(false);
        config.setChildrenCount(0);
        when(employeeConfigRepo.findByProfileUserId(1L)).thenReturn(Optional.of(config));

        when(resultRepo.save(any())).thenAnswer(inv -> {
            PayrollResult r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        // Bridge is best-effort and swallows failures (PayrollOrchestrator#injectChargeVariables'
        // own try/catch) — leave parameterSetService unstubbed so it throws, proving the bridge's
        // output plays no role in this test's assertions.
        when(parameterSetService.loadActiveEntity(179L)).thenThrow(new IllegalStateException("no old-model set"));

        PayrollOrchestrator orchestrator = new PayrollOrchestrator(
            new ContextLoader(countryRepo, paramSetRepo, new ObjectMapper()),
            new SequenceValidator(),
            new CalculationDispatcher(List.of(
                new MontantFixeCalculator(), new TauxPctCalculator(), new BaremeProgressifCalculator(),
                new AnnualiseBaremeCalculator(), new FormuleCalculator(), new NewtonRaphsonCalculator())),
            new SystemVariableAggregator(),
            new ResultPersister(resultRepo, new ObjectMapper()),
            forexService,
            rubriqueRepo, countryRepo, new TopologicalEvaluator(null), parameterSetService, employeeConfigRepo);

        RunPayrollRequest request = new RunPayrollRequest();
        request.setEmployeeId(1L);
        request.setPaysId(179L);
        request.setPeriodYear(2026);
        request.setPeriodMonth(1);
        request.setContractTypeCode("CDI");

        RunPayrollResponse response = orchestrator.run(request);

        // NET matches the old model's validated reference exactly: net only depends on
        // gross, employee-side charges, and taxes — none of which differ between the two
        // engines once rates are corrected (an earlier task's migration).
        assertThat(response.getAggregateNet()).isCloseTo(new BigDecimal("415.40"), within(new BigDecimal("0.01")));

        // LOADED COST must NOT match the old model's 543.17 — that figure structurally
        // excludes TFP and FOPROLOS (no old-model equivalent exists at all). This is the
        // actual bug-3 regression bar: gross + CNSS_PATRONAL(16.57%) + TFP(2%) +
        // FOPROLOS(1%) + AT_PATRONAL(1%) on a 462 gross = 462 + 76.55 + 9.24 + 4.62 +
        // 4.62 = 557.03. If this test ever asserts 543.17 instead, that is the OLD bug
        // silently reintroduced (TFP/FOPROLOS dropped again).
        assertThat(response.getAggregateEmployerCharges()).isCloseTo(new BigDecimal("95.03"), within(new BigDecimal("0.01")));
        assertThat(response.getLoadedCost()).isCloseTo(new BigDecimal("557.03"), within(new BigDecimal("0.01")));
    }

    @Test
    void egyptIncomeTax_noLongerHardZero_butNotClaimedCorrect() {
        PayrollCountry country = new PayrollCountry();
        country.setId(8L);
        country.setPaysId(53L);
        when(countryRepo.findByPaysIdAndActiveTrue(53L)).thenReturn(Optional.of(country));

        PayrollParamSet paramSet = new PayrollParamSet();
        paramSet.setId(8L);
        paramSet.setCountryId(8L);
        paramSet.setStatus("ACTIVE");
        paramSet.setVersionNumber(1);
        paramSet.setEffectiveDate(LocalDate.of(2026, 1, 1));
        paramSet.setParameters("{\"salaire_base_eg\":15000.0,\"taux_si_sal_eg\":11.0,"
            + "\"taux_si_empl_eg\":18.75,\"plafond_si_mens_eg\":10900.0,\"taux_shif_eg\":3.0,"
            + "\"bareme_impot_eg\":[{\"min\":0,\"max\":40000,\"rate\":0},"
            + "{\"min\":40000,\"max\":55000,\"rate\":10},{\"min\":55000,\"max\":70000,\"rate\":15}]}");
        when(paramSetRepo.findByCountryIdAndStatus(8L, "ACTIVE")).thenReturn(Optional.of(paramSet));

        when(rubriqueRepo.findByCountryIdAndActiveTrue(8L)).thenReturn(List.of(
            rubrique("SALAIRE_BASE", 1, "GAIN", "MONTANT_FIXE", null, "salaire_base_eg", null, null, null, null, 10),
            rubrique("SI_SALARIE", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_si_sal_eg", "plafond_si_mens_eg", null, null, "EMPLOYEE", 20),
            rubrique("SI_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_si_empl_eg", "plafond_si_mens_eg", null, null, "EMPLOYER", 25),
            rubrique("SHIF_PATRONAL", 3, "COTISATION", "TAUX_PCT", "STRATE_1", "taux_shif_eg", null, null, null, "EMPLOYER", 30),
            rubrique("ASSIETTE_INCOME_TAX", 4, "TAXE", "FORMULE", null, null, null, null,
                "T(Math).max((BRUT + AVANTAGES - SI_SALARIE) * 12, 0.0) / 12", null, 35),
            rubrique("INCOME_TAX", 5, "TAXE", "ANNUALISE_BAREME", "STRATE_4", null, null, "bareme_impot_eg", null, null, 40)
        ));

        EmployeePayrollConfig config = new EmployeePayrollConfig();
        config.setCurrentGrossSalary(new BigDecimal("15000"));
        config.setIsMarried(false);
        config.setChildrenCount(0);
        when(employeeConfigRepo.findByProfileUserId(2L)).thenReturn(Optional.of(config));

        when(resultRepo.save(any())).thenAnswer(inv -> {
            PayrollResult r = inv.getArgument(0);
            r.setId(2L);
            return r;
        });
        when(parameterSetService.loadActiveEntity(53L)).thenThrow(new IllegalStateException("no old-model set"));

        PayrollOrchestrator orchestrator = new PayrollOrchestrator(
            new ContextLoader(countryRepo, paramSetRepo, new ObjectMapper()),
            new SequenceValidator(),
            new CalculationDispatcher(List.of(
                new MontantFixeCalculator(), new TauxPctCalculator(), new BaremeProgressifCalculator(),
                new AnnualiseBaremeCalculator(), new FormuleCalculator(), new NewtonRaphsonCalculator())),
            new SystemVariableAggregator(),
            new ResultPersister(resultRepo, new ObjectMapper()),
            forexService,
            rubriqueRepo, countryRepo, new TopologicalEvaluator(null), parameterSetService, employeeConfigRepo);

        RunPayrollRequest request = new RunPayrollRequest();
        request.setEmployeeId(2L);
        request.setPaysId(53L);
        request.setPeriodYear(2026);
        request.setPeriodMonth(1);
        request.setContractTypeCode("CDI");

        RunPayrollResponse response = orchestrator.run(request);

        // Structural check ONLY — Egypt's rates/formula are explicitly unvalidated.
        assertThat(response.getStrate5()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.getAggregateNet()).isLessThan(response.getAggregateGross());
    }
}
