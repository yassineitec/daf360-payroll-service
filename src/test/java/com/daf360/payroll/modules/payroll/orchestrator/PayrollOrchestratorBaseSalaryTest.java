package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayrollOrchestratorBaseSalaryTest {

    private static PayrollRubriqueDef rubrique(String code, int strate, String mode, String key) {
        PayrollRubriqueDef r = new PayrollRubriqueDef();
        r.setCode(code);
        r.setStrate(strate);
        r.setModeCalcul(mode);
        r.setParamKeyTaux(key);
        return r;
    }

    @Test
    void baseSalary_prefersTheUsualCode() {
        List<PayrollRubriqueDef> rubriques = List.of(
            rubrique("FORFAIT", 1, "MONTANT_FIXE", "FORFAIT_MONTANT"),
            rubrique("SALAIRE_BASE", 1, "MONTANT_FIXE", "SALAIRE_BASE_MONTANT"));

        assertEquals("SALAIRE_BASE", PayrollOrchestrator.baseSalaryRubrique(rubriques).orElseThrow().getCode());
    }

    @Test
    void baseSalary_otherwiseTheFirstFixedAmountOfStrate1() {
        List<PayrollRubriqueDef> rubriques = List.of(
            rubrique("TRANSPORT", 2, "MONTANT_FIXE", "PRIME_TRANSPORT_MONTANT"),
            rubrique("SALAIRE_DE_BASE", 1, "MONTANT_FIXE", "SALAIRE_BASE_MONTANT"),
            rubrique("CNSS_SALARIE", 3, "TAUX_PCT", "TAUX_CNSS_EE"));

        assertEquals("SALAIRE_DE_BASE", PayrollOrchestrator.baseSalaryRubrique(rubriques).orElseThrow().getCode());
    }

    @Test
    void baseSalary_noneWithoutFixedAmountInStrate1() {
        List<PayrollRubriqueDef> rubriques = List.of(rubrique("TRANSPORT", 2, "MONTANT_FIXE", "PRIME_TRANSPORT_MONTANT"));

        assertTrue(PayrollOrchestrator.baseSalaryRubrique(rubriques).isEmpty());
    }
}
