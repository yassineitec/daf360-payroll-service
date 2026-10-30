package com.daf360.payroll.modules.calibration.service;

import com.daf360.payroll.modules.admin.service.EngineConfigService;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Manages the DRAFT → SUBMITTED → APPROVED_HR → APPROVED_FINANCE → ACTIVE → ARCHIVED
 * lifecycle for PayrollParamSet.
 */
@Service
public class ParameterSetWorkflowService {

    private final PayrollParamSetRepository repo;
    private final EngineConfigService engineConfig;

    public ParameterSetWorkflowService(PayrollParamSetRepository repo, EngineConfigService engineConfig) {
        this.repo = repo;
        this.engineConfig = engineConfig;
    }

    /**
     * Soumission d'un brouillon. Refusée (400) si une rubrique active du pays utilise une clé
     * de paramètre absente du jeu : activé tel quel, il ferait échouer le calcul de paie.
     */
    public PayrollParamSet submit(Long id, String submittedBy) {
        PayrollParamSet ps = findOrThrow(id);
        assertStatus(ps, "DRAFT");
        Set<String> missing = engineConfig.missingKeys(ps.getCountryId(), ps.getParameters());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "Paramètres manquants, utilisés par des rubriques actives : " + String.join(", ", missing));
        }
        ps.setStatus("SUBMITTED");
        ps.setSubmittedBy(submittedBy);
        ps.setSubmittedAt(OffsetDateTime.now());
        return repo.save(ps);
    }

    public PayrollParamSet approveHr(Long id, String approvedBy) {
        PayrollParamSet ps = findOrThrow(id);
        assertStatus(ps, "SUBMITTED");
        ps.setStatus("APPROVED_HR");
        ps.setApprovedByHr(approvedBy);
        ps.setApprovedAtHr(OffsetDateTime.now());
        return repo.save(ps);
    }

    public PayrollParamSet approveFinance(Long id, String approvedBy) {
        PayrollParamSet ps = findOrThrow(id);
        assertStatus(ps, "APPROVED_HR");
        ps.setStatus("APPROVED_FINANCE");
        ps.setApprovedByFinance(approvedBy);
        ps.setApprovedAtFinance(OffsetDateTime.now());
        return repo.save(ps);
    }

    @Transactional
    public PayrollParamSet activate(Long id, String activatedBy) {
        PayrollParamSet ps = findOrThrow(id);
        assertStatus(ps, "APPROVED_FINANCE");

        // Archive currently ACTIVE set for this country
        repo.findByCountryIdAndStatus(ps.getCountryId(), "ACTIVE").ifPresent(active -> {
            active.setStatus("ARCHIVED");
            active.setArchivedAt(OffsetDateTime.now());
            repo.save(active);
        });

        ps.setStatus("ACTIVE");
        ps.setActivatedAt(OffsetDateTime.now());
        return repo.save(ps);
    }

    public PayrollParamSet archive(Long id) {
        PayrollParamSet ps = findOrThrow(id);
        ps.setStatus("ARCHIVED");
        ps.setArchivedAt(OffsetDateTime.now());
        return repo.save(ps);
    }

    public List<PayrollParamSet> list(Long countryId) {
        return repo.findByCountryIdOrderByVersionNumberDesc(countryId);
    }

    private PayrollParamSet findOrThrow(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Jeu de paramètres introuvable : " + id));
    }

    private void assertStatus(PayrollParamSet ps, String expected) {
        if (!expected.equals(ps.getStatus())) {
            throw new IllegalStateException(
                "Expected status=" + expected + " but was " + ps.getStatus()
                    + " for id=" + ps.getId());
        }
    }
}
