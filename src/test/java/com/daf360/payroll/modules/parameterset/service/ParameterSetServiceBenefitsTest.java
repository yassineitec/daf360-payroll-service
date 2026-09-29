package com.daf360.payroll.modules.parameterset.service;

import com.daf360.payroll.modules.parameterset.dto.BenefitCatalogueDto;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.repository.BenefitCatalogueRepository;
import com.daf360.payroll.modules.parameterset.repository.ParameterSetRepository;
import com.daf360.payroll.modules.parameterset.repository.PayrollRubriqueRepository;
import com.daf360.payroll.modules.parameterset.repository.SocialChargeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParameterSetServiceBenefitsTest {

    @Mock ParameterSetRepository     paramSetRepo;
    @Mock SocialChargeRateRepository rateRepo;
    @Mock BenefitCatalogueRepository benefitRepo;
    @Mock PayrollRubriqueRepository  rubriqueRepo;
    @Mock ApplicationEventPublisher  eventPublisher;

    ParameterSetService service;

    @BeforeEach
    void setUp() {
        service = new ParameterSetService(paramSetRepo, rateRepo, benefitRepo, rubriqueRepo, eventPublisher);
        lenient().when(rateRepo.findByParameterSetId(anyLong())).thenReturn(List.of());
        lenient().when(benefitRepo.findByParameterSetId(anyLong())).thenReturn(List.of());
        lenient().when(rubriqueRepo.findByParameterSetIdAndIsActiveTrue(anyLong())).thenReturn(List.of());
        lenient().when(rubriqueRepo.findByParameterSetId(anyLong())).thenReturn(List.of());
    }

    private void givenSet(String status) {
        ParameterSet ps = new ParameterSet();
        ps.setId(1L);
        ps.setPaysId(179L);
        ps.setVersion(1);
        ps.setFiscalYear(2026);
        ps.setStatus(status);
        when(paramSetRepo.findById(1L)).thenReturn(Optional.of(ps));
    }

    private static BenefitCatalogueDto benefit(String code, String label, String monthly) {
        return new BenefitCatalogueDto(null, code, label, null, "TAX_AUTHORITY",
                monthly == null ? null : new BigDecimal(monthly), null, null, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateBenefits_draft_replacesTheList_defaultingMissingAmountsToZero() {
        givenSet("DRAFT");

        service.updateBenefits(1L, List.of(benefit(" MEAL ", "Repas", "120.500"), benefit("TRANSPORT", "Transport", null)));

        verify(benefitRepo).deleteByParameterSetId(1L);
        ArgumentCaptor<List<BenefitCatalogue>> saved = ArgumentCaptor.forClass(List.class);
        verify(benefitRepo).saveAll(saved.capture());
        assertEquals(2, saved.getValue().size());
        assertEquals("MEAL", saved.getValue().get(0).getBenefitCode());
        assertEquals(new BigDecimal("120.500"), saved.getValue().get(0).getMonthlyValue());
        assertEquals(BigDecimal.ZERO, saved.getValue().get(1).getMonthlyValue());
        assertEquals(BigDecimal.ZERO, saved.getValue().get(1).getEmployeeShare());
    }

    @Test
    void updateBenefits_activeSet_isRefused() {
        givenSet("ACTIVE");

        assertThrows(IllegalStateException.class,
                () -> service.updateBenefits(1L, List.of(benefit("MEAL", "Repas", "10"))));
        verify(benefitRepo, never()).saveAll(any());
    }

    @Test
    void updateBenefits_duplicateCode_isRefused() {
        givenSet("DRAFT");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateBenefits(1L, List.of(benefit("MEAL", "Repas", "10"), benefit("meal", "Repas 2", "5"))));
        verify(benefitRepo, never()).deleteByParameterSetId(anyLong());
    }

    @Test
    void updateBenefits_negativeAmount_isRefused() {
        givenSet("DRAFT");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateBenefits(1L, List.of(benefit("MEAL", "Repas", "-1"))));
    }
    // ── Barème IRPP à la création ─────────────────────────────────────────

    @Test
    void validateIrppBrackets_acceptsTheEngineFormat() {
        ParameterSetService.validateIrppBrackets(
            "[{\"lower\":0,\"upper\":5000,\"rate\":0},{\"lower\":5000,\"upper\":null,\"rate\":0.26}]");
        ParameterSetService.validateIrppBrackets("[{\"min\":0,\"max\":null,\"rate\":26}]");
    }

    @Test
    void validateIrppBrackets_rejectsBadScales() {
        for (String bad : List.of(
                "", "{}", "[]", "pas du json",
                "[{'lower':0,'rate':0.1}]",
                "[{\"lower\":0,\"upper\":null,\"rate\":0.1},{\"lower\":5000,\"upper\":null,\"rate\":0.2}]",
                "[{\"lower\":5000,\"upper\":1000,\"rate\":0.1}]",
                "[{\"lower\":0,\"upper\":5000,\"rate\":0},{\"lower\":4000,\"upper\":null,\"rate\":0.2}]",
                "[{\"lower\":0,\"upper\":null,\"rate\":-1}]")) {
            assertThrows(IllegalArgumentException.class, () -> ParameterSetService.validateIrppBrackets(bad), bad);
        }
    }
}
