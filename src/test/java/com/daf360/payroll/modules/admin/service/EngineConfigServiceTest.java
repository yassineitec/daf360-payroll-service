package com.daf360.payroll.modules.admin.service;

import com.daf360.payroll.modules.admin.dto.SaveEngineParamSetRequest;
import com.daf360.payroll.modules.admin.dto.SaveEngineRubriqueRequest;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollRubriqueDefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PaysScopeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngineConfigServiceTest {

    @Mock PayrollCountryRepository     countryRepo;
    @Mock PayrollParamSetRepository    paramSetRepo;
    @Mock PayrollRubriqueDefRepository rubriqueRepo;
    @Mock UserContextService           userContext;

    EngineConfigService service;

    @BeforeEach
    void setUp() {
        service = new EngineConfigService(countryRepo, paramSetRepo, rubriqueRepo, userContext);
        lenient().when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(179L)));
        PayrollCountry tn = new PayrollCountry();
        tn.setId(1L);
        tn.setPaysId(179L);
        tn.setCurrencyCode("TND");
        lenient().when(countryRepo.findByPaysId(179L)).thenReturn(Optional.of(tn));
        lenient().when(countryRepo.findById(1L)).thenReturn(Optional.of(tn));
        lenient().when(paramSetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rubriqueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PayrollParamSet version(long id, int number, String status, String params) {
        PayrollParamSet ps = new PayrollParamSet();
        ps.setId(id);
        ps.setCountryId(1L);
        ps.setVersionNumber(number);
        ps.setStatus(status);
        ps.setParameters(params);
        return ps;
    }

    // ── Paramètres ──────────────────────────────────────────────────────────

    @Test
    void normaliseParameters_acceptsNumbersAndBaremes() {
        String out = EngineConfigService.normaliseParameters(
            "{ \"CNSS_TAUX\": 9.18, \"IRPP\": [ {\"min\":0,\"max\":5000,\"rate\":0}, {\"min\":5000,\"max\":null,\"rate\":26} ] }");
        assertEquals("{\"CNSS_TAUX\":9.18,\"IRPP\":[{\"min\":0,\"max\":5000,\"rate\":0},{\"min\":5000,\"max\":null,\"rate\":26}]}", out);
    }

    @Test
    void normaliseParameters_rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> EngineConfigService.normaliseParameters("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> EngineConfigService.normaliseParameters("{pas du json"));
        assertThrows(IllegalArgumentException.class, () -> EngineConfigService.normaliseParameters("{\"B\":[{\"min\":10,\"max\":5,\"rate\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> EngineConfigService.normaliseParameters("{\"B\":[{\"rate\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> EngineConfigService.normaliseParameters("{\"bad key\":1}"));
        assertThrows(IllegalArgumentException.class, () -> EngineConfigService.normaliseParameters("{\"X\":{\"a\":1}}"));
    }

    @Test
    void createDraft_copiesTheActiveVersion_withTheNextNumber() {
        when(paramSetRepo.findByCountryIdOrderByVersionNumberDesc(1L)).thenReturn(List.of(
            version(3, 3, "ARCHIVED", "{\"A\":3}"), version(2, 2, "ACTIVE", "{\"A\":2}")));

        PayrollParamSet draft = service.createDraft(179L, "Admin");

        assertEquals("DRAFT", draft.getStatus());
        assertEquals(4, draft.getVersionNumber());
        assertEquals("{\"A\":2}", draft.getParameters());
        assertEquals("Admin", draft.getCreatedBy());
    }

    @Test
    void createDraft_whenADraftExists_isRefused() {
        when(paramSetRepo.findByCountryIdOrderByVersionNumberDesc(1L)).thenReturn(List.of(version(5, 5, "DRAFT", "{}")));

        assertThrows(IllegalStateException.class, () -> service.createDraft(179L, "Admin"));
        verify(paramSetRepo, never()).save(any());
    }

    @Test
    void createDraft_outOfScope_isRefused() {
        when(userContext.getCurrentUserPaysScope()).thenReturn(PaysScopeContext.PaysScope.of(Set.of(52L)));

        assertThrows(AccessDeniedException.class, () -> service.createDraft(179L, "Admin"));
    }

    @Test
    void updateDraft_onlyForDrafts() {
        when(paramSetRepo.findById(2L)).thenReturn(Optional.of(version(2, 2, "ACTIVE", "{}")));

        assertThrows(IllegalStateException.class,
            () -> service.updateDraft(2L, new SaveEngineParamSetRequest("{\"A\":1}", null)));
        verify(paramSetRepo, never()).save(any());
    }

    @Test
    void missingKeys_listsKeysUsedByActiveRubriques() {
        PayrollRubriqueDef cnss = new PayrollRubriqueDef();
        cnss.setParamKeyTaux("CNSS_TAUX");
        cnss.setParamKeyPlafond("CNSS_PLAFOND");
        PayrollRubriqueDef irpp = new PayrollRubriqueDef();
        irpp.setParamKeyBareme("IRPP");
        when(rubriqueRepo.findByCountryIdAndActiveTrue(1L)).thenReturn(List.of(cnss, irpp));

        assertEquals(Set.of("CNSS_PLAFOND", "IRPP"), service.missingKeys(1L, "{\"CNSS_TAUX\":9.18}"));
    }

    // ── Rubriques ───────────────────────────────────────────────────────────

    private static SaveEngineRubriqueRequest rubrique(String code, String mode, String taux, String bareme, String formula) {
        return new SaveEngineRubriqueRequest(code, "Libellé", null, 3, "RETENUE", mode, "BRUT",
            taux, null, bareme, formula, null, null, false, 10, true);
    }

    @Test
    void createRubrique_normalisesAndSaves() {
        when(rubriqueRepo.findByCountryIdAndCode(1L, "CNSS_EE")).thenReturn(Optional.empty());

        PayrollRubriqueDef r = service.createRubrique(179L, rubrique(" cnss_ee ", "TAUX_PCT", "CNSS_TAUX", null, null));

        assertEquals("CNSS_EE", r.getCode());
        assertEquals(1L, r.getCountryId());
        assertEquals("MENSUEL", r.getPeriodicite());
    }

    @Test
    void createRubrique_rejectsInconsistentModes() {
        lenient().when(rubriqueRepo.findByCountryIdAndCode(any(), any())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.createRubrique(179L, rubrique("A", "TAUX_PCT", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.createRubrique(179L, rubrique("B", "BAREME_PROGRESSIF", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.createRubrique(179L, rubrique("C", "FORMULE", null, null, "STRATE_1 * (")));
        assertThrows(IllegalArgumentException.class, () -> service.createRubrique(179L, rubrique("D", "INCONNU", "X", null, null)));
        verify(rubriqueRepo, never()).save(any());
    }

    @Test
    void createRubrique_duplicateCode_isRefused() {
        when(rubriqueRepo.findByCountryIdAndCode(1L, "CNSS_EE")).thenReturn(Optional.of(new PayrollRubriqueDef()));

        assertThrows(IllegalStateException.class,
            () -> service.createRubrique(179L, rubrique("CNSS_EE", "TAUX_PCT", "CNSS_TAUX", null, null)));
    }
}
