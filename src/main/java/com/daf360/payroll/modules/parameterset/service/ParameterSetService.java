package com.daf360.payroll.modules.parameterset.service;

import com.daf360.payroll.modules.calibration.event.ParameterSetActivatedEvent;
import com.daf360.payroll.modules.parameterset.dto.*;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.repository.BenefitCatalogueRepository;
import com.daf360.payroll.modules.parameterset.repository.ParameterSetRepository;
import com.daf360.payroll.modules.parameterset.repository.PayrollRubriqueRepository;
import com.daf360.payroll.modules.parameterset.repository.SocialChargeRateRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ParameterSetService {

    private final ParameterSetRepository    paramSetRepo;
    private final SocialChargeRateRepository rateRepo;
    private final BenefitCatalogueRepository benefitRepo;
    private final PayrollRubriqueRepository  rubriqueRepo;
    private final ApplicationEventPublisher  eventPublisher;

    public ParameterSetService(ParameterSetRepository paramSetRepo,
                                SocialChargeRateRepository rateRepo,
                                BenefitCatalogueRepository benefitRepo,
                                PayrollRubriqueRepository rubriqueRepo,
                                ApplicationEventPublisher eventPublisher) {
        this.paramSetRepo   = paramSetRepo;
        this.rateRepo       = rateRepo;
        this.benefitRepo    = benefitRepo;
        this.rubriqueRepo   = rubriqueRepo;
        this.eventPublisher = eventPublisher;
    }

    public List<ParameterSetDto> listByPays(Long paysId) {
        return paramSetRepo.findByPaysIdOrderByVersionDesc(paysId).stream()
                .map(this::toDto)
                .toList();
    }

    public ParameterSetDto getActiveByPays(Long paysId) {
        ParameterSet ps = paramSetRepo
                .findFirstByPaysIdAndStatusOrderByVersionDesc(paysId, "ACTIVE")
                .orElseThrow(() -> new NoSuchElementException(
                        "No ACTIVE parameter set for paysId=" + paysId));
        return toDto(ps);
    }

    public ParameterSetDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional
    public ParameterSetDto create(CreateParameterSetRequest req, Long createdBy) {
        Integer nextVersion = paramSetRepo
                .findTopByPaysIdOrderByVersionDesc(req.paysId())
                .map(p -> p.getVersion() + 1)
                .orElse(1);

        ParameterSet ps = new ParameterSet();
        ps.setPaysId(req.paysId());
        ps.setVersion(nextVersion);
        ps.setFiscalYear(req.fiscalYear());
        ps.setIrppBrackets(req.irppBrackets());
        ps.setStatus("DRAFT");
        ps.setChangeRationale(req.changeRationale());
        ps.setCreatedBy(createdBy);

        if (req.convergenceTolerance() != null) ps.setConvergenceTolerance(req.convergenceTolerance());
        if (req.maxConvergenceIterations() != null) ps.setMaxConvergenceIterations(req.maxConvergenceIterations());
        if (req.calibrationThresholdPct() != null) ps.setCalibrationThresholdPct(req.calibrationThresholdPct());

        ps = paramSetRepo.save(ps);

        if (req.socialChargeRates() != null) saveRates(ps.getId(), req.socialChargeRates());
        if (req.benefits() != null) saveBenefits(ps.getId(), req.benefits());
        if (req.rubriques() != null) saveRubriques(ps.getId(), req.rubriques());

        return toDto(ps);
    }

    @Transactional
    public ParameterSetDto submitForApproval(Long id) {
        ParameterSet ps = findOrThrow(id);
        assertStatus(ps, "DRAFT");
        ps.setStatus("PENDING_FINANCE");
        return toDto(paramSetRepo.save(ps));
    }

    @Transactional
    public ParameterSetDto approveHr(Long id, Long approverUserId) {
        ParameterSet ps = findOrThrow(id);
        assertStatus(ps, "PENDING_FINANCE");
        ps.setApprovedByHr(approverUserId);
        if (ps.getApprovedByFinance() != null) activate(ps);
        return toDto(paramSetRepo.save(ps));
    }

    @Transactional
    public ParameterSetDto updateSocialChargeRates(Long id, List<SocialChargeRateDto> rates) {
        findOrThrow(id);
        saveRates(id, rates);
        return toDto(findOrThrow(id));
    }

    @Transactional
    public ParameterSetDto updateRubriques(Long id, List<SavePayrollRubriqueRequest> rubriques) {
        findOrThrow(id);
        saveRubriques(id, rubriques);
        return toDto(findOrThrow(id));
    }

    @Transactional
    public ParameterSetDto approveFinance(Long id, Long approverUserId) {
        ParameterSet ps = findOrThrow(id);
        assertStatus(ps, "PENDING_FINANCE");
        ps.setApprovedByFinance(approverUserId);
        if (ps.getApprovedByHr() != null) activate(ps);
        return toDto(paramSetRepo.save(ps));
    }

    private void activate(ParameterSet ps) {
        paramSetRepo
                .findFirstByPaysIdAndStatusOrderByVersionDesc(ps.getPaysId(), "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("ARCHIVED");
                    paramSetRepo.save(old);
                });

        ps.setStatus("ACTIVE");
        ps.setApprovedAt(OffsetDateTime.now());
        ps.setActivatedAt(OffsetDateTime.now());
        ps.setPreviousVersionId(
                paramSetRepo.findFirstByPaysIdAndStatusOrderByVersionDesc(ps.getPaysId(), "ARCHIVED")
                        .map(ParameterSet::getId).orElse(null));

        // Trigger F.04 + F.07 finance output generation after this transaction commits.
        eventPublisher.publishEvent(new ParameterSetActivatedEvent(ps));
    }

    // -----------------------------------------------------------------------
    //  Entity loading helpers used by SimulationModule
    // -----------------------------------------------------------------------

    public ParameterSet loadActiveEntity(Long paysId) {
        return paramSetRepo
                .findFirstByPaysIdAndStatusOrderByVersionDesc(paysId, "ACTIVE")
                .orElseThrow(() -> new NoSuchElementException(
                        "No ACTIVE parameter set for paysId=" + paysId));
    }

    public List<SocialChargeRate> loadRates(Long parameterSetId) {
        return rateRepo.findByParameterSetId(parameterSetId);
    }

    public List<BenefitCatalogue> loadBenefits(Long parameterSetId) {
        return benefitRepo.findByParameterSetId(parameterSetId);
    }

    public List<PayrollRubrique> loadRubriques(Long parameterSetId) {
        return rubriqueRepo.findByParameterSetIdAndIsActiveTrue(parameterSetId);
    }

    // -----------------------------------------------------------------------
    //  Private helpers
    // -----------------------------------------------------------------------

    private void saveRates(Long psId, List<SocialChargeRateDto> dtos) {
        rateRepo.deleteByParameterSetId(psId);
        List<SocialChargeRate> entities = dtos.stream().map(d -> {
            SocialChargeRate r = new SocialChargeRate();
            r.setParameterSetId(psId);
            r.setContractType(d.contractType());
            r.setChargeCode(d.chargeCode());
            r.setChargeLabel(d.chargeLabel());
            r.setEmployeeRate(d.employeeRate() != null ? d.employeeRate() : java.math.BigDecimal.ZERO);
            r.setEmployerRate(d.employerRate() != null ? d.employerRate() : java.math.BigDecimal.ZERO);
            r.setBaseCalculation(d.baseCalculation() != null ? d.baseCalculation() : "GROSS");
            r.setCapAmount(d.capAmount());
            // V19 formula fields
            r.setFormulaEe(d.formulaEe() != null && !d.formulaEe().isBlank() ? d.formulaEe().trim() : null);
            r.setFormulaEr(d.formulaEr() != null && !d.formulaEr().isBlank() ? d.formulaEr().trim() : null);
            r.setEvalOrder(d.evalOrder() != null ? d.evalOrder() : 0);
            return r;
        }).toList();
        rateRepo.saveAll(entities);
    }

    private void saveBenefits(Long psId, List<BenefitCatalogueDto> dtos) {
        benefitRepo.deleteByParameterSetId(psId);
        List<BenefitCatalogue> entities = dtos.stream().map(d -> {
            BenefitCatalogue b = new BenefitCatalogue();
            b.setParameterSetId(psId);
            b.setBenefitCode(d.benefitCode());
            b.setBenefitLabelFr(d.benefitLabelFr());
            b.setBenefitLabelEn(d.benefitLabelEn());
            b.setValuationMethod(d.valuationMethod() != null ? d.valuationMethod() : "TAX_AUTHORITY");
            b.setMonthlyValue(d.monthlyValue());
            b.setEmployeeShare(d.employeeShare());
            b.setEmployerShare(d.employerShare());
            b.setIsTaxable(d.isTaxable() != null ? d.isTaxable() : true);
            return b;
        }).toList();
        benefitRepo.saveAll(entities);
    }

    private void saveRubriques(Long psId, List<SavePayrollRubriqueRequest> dtos) {
        rubriqueRepo.deleteByParameterSetId(psId);
        List<PayrollRubrique> entities = dtos.stream().map(d -> {
            PayrollRubrique r = new PayrollRubrique();
            r.setParameterSetId(psId);
            r.setCode(d.code());
            r.setLabelFr(d.labelFr());
            r.setLabelEn(d.labelEn());
            r.setNature(d.nature());
            r.setCalcMode(d.calcMode());
            r.setAmount(d.amount());
            r.setRate(d.rate());
            r.setCapAmount(d.capAmount());
            r.setEmployerSharePct(d.employerSharePct() != null ? d.employerSharePct() : BigDecimal.ZERO);
            r.setEmployeeSharePct(d.employeeSharePct() != null ? d.employeeSharePct() : BigDecimal.ZERO);
            r.setIsSubjectToSocialCharges(d.isSubjectToSocialCharges() != null ? d.isSubjectToSocialCharges() : false);
            r.setIsSubjectToIrpp(d.isSubjectToIrpp() != null ? d.isSubjectToIrpp() : true);
            r.setDirection(d.direction() != null ? d.direction() : "CREDIT");
            r.setContractTypes(d.contractTypes());
            r.setIsActive(d.isActive() != null ? d.isActive() : true);
            r.setFormulaExpression(d.formulaExpression());
            r.setDisplayOrder(d.displayOrder() != null ? d.displayOrder() : 0);
            return r;
        }).toList();
        rubriqueRepo.saveAll(entities);
    }

    private ParameterSet findOrThrow(Long id) {
        return paramSetRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ParameterSet not found: " + id));
    }

    private void assertStatus(ParameterSet ps, String expected) {
        if (!expected.equals(ps.getStatus())) {
            throw new IllegalStateException(
                    "Expected status " + expected + " but was " + ps.getStatus());
        }
    }

    private ParameterSetDto toDto(ParameterSet ps) {
        List<SocialChargeRateDto> rates = rateRepo.findByParameterSetId(ps.getId()).stream()
                .map(r -> new SocialChargeRateDto(r.getId(), r.getContractType(),
                        r.getChargeCode(), r.getChargeLabel(),
                        r.getEmployeeRate(), r.getEmployerRate(),
                        r.getBaseCalculation(), r.getCapAmount(),
                        r.getFormulaEe(), r.getFormulaEr(), r.getEvalOrder()))
                .toList();

        List<BenefitCatalogueDto> benefits = benefitRepo.findByParameterSetId(ps.getId()).stream()
                .map(b -> new BenefitCatalogueDto(b.getId(), b.getBenefitCode(),
                        b.getBenefitLabelFr(), b.getBenefitLabelEn(),
                        b.getValuationMethod(), b.getMonthlyValue(),
                        b.getEmployeeShare(), b.getEmployerShare(), b.getIsTaxable()))
                .toList();

        List<PayrollRubriqueDto> rubriques = rubriqueRepo.findByParameterSetId(ps.getId()).stream()
                .map(r -> new PayrollRubriqueDto(r.getId(), r.getCode(),
                        r.getLabelFr(), r.getLabelEn(), r.getNature(), r.getCalcMode(),
                        r.getAmount(), r.getRate(), r.getCapAmount(),
                        r.getEmployerSharePct(), r.getEmployeeSharePct(),
                        r.getIsSubjectToSocialCharges(), r.getIsSubjectToIrpp(),
                        r.getDirection(), r.getContractTypes(), r.getIsActive(),
                        r.getFormulaExpression(), r.getDisplayOrder(), r.getCreatedAt()))
                .toList();

        return new ParameterSetDto(
                ps.getId(), ps.getPaysId(), ps.getVersion(), ps.getFiscalYear(),
                ps.getStatus(), ps.getIrppBrackets(), ps.getConvergenceTolerance(),
                ps.getMaxConvergenceIterations(), ps.getCalibrationThresholdPct(),
                ps.getApprovedByHr(), ps.getApprovedByFinance(),
                ps.getApprovedAt(), ps.getActivatedAt(), ps.getChangeRationale(),
                ps.getPreviousVersionId(), ps.getCreatedAt(),
                rates, benefits, rubriques);
    }
}
