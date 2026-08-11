package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.dto.CohortSimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationMode;
import com.daf360.payroll.modules.simulation.dto.SimulationResultDto;
import com.daf360.payroll.modules.simulation.entity.CohortDefinition;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.CohortDefinitionRepository;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CohortSimulationService {

    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;
    private final FxSnapshotService fxService;
    private final SimulationResultRepository resultRepo;
    private final CohortDefinitionRepository cohortRepo;
    private final ObjectMapper objectMapper;

    public CohortSimulationService(ParameterSetService paramSetService,
                                    PayrollSimulatorService simulatorService,
                                    FxSnapshotService fxService,
                                    SimulationResultRepository resultRepo,
                                    CohortDefinitionRepository cohortRepo,
                                    ObjectMapper objectMapper) {
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
        this.fxService = fxService;
        this.resultRepo = resultRepo;
        this.cohortRepo = cohortRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<SimulationResultDto> simulate(CohortSimulationRequest req, Long simulatedBy) {
        ParameterSet ps = paramSetService.loadActiveEntity(req.paysId());
        List<SocialChargeRate> rates = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> benefits = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique> rubriques = paramSetService.loadRubriques(ps.getId());

        CohortDefinition cohort = createCohort(req, ps.getId(), simulatedBy);

        SimulationMode mode = req.mode() != null ? req.mode() : SimulationMode.NET_TO_BRUT;

        List<SimulationResult> results = new ArrayList<>();
        BigDecimal totalLoadedCost = BigDecimal.ZERO;

        for (CohortSimulationRequest.EmployeeSimEntry entry : req.employees()) {
            String contractType = entry.contractType() != null ? entry.contractType() : "CDI";

            PayrollSimulatorService.PayrollResult calc;
            if (mode == SimulationMode.BRUT_TO_NET) {
                if (entry.inputGross() == null || entry.inputGross().compareTo(BigDecimal.ZERO) <= 0)
                    throw new IllegalArgumentException(
                            "inputGross est requis pour chaque entrée en mode BRUT_TO_NET" +
                            " (profileUserId=" + entry.profileUserId() + ").");
                calc = simulatorService.computeFromGross(
                        entry.inputGross(), ps, rates, benefits, rubriques, contractType, 22);
            } else {
                if (entry.inputNet() == null || entry.inputNet().compareTo(BigDecimal.ZERO) <= 0)
                    throw new IllegalArgumentException(
                            "inputNet est requis pour chaque entrée en mode NET_TO_BRUT" +
                            " (profileUserId=" + entry.profileUserId() + ").");
                calc = simulatorService.computeFromNet(
                        entry.inputNet(), ps, rates, benefits, rubriques, contractType, 22);
            }

            BigDecimal netInHand = calc.netInHand();
            BigDecimal exemptBenefits = benefits.stream()
                    .filter(b -> Boolean.FALSE.equals(b.getIsTaxable()))
                    .map(b -> b.getMonthlyValue() != null ? b.getMonthlyValue() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grossWithBen = calc.gross().add(exemptBenefits)
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            BigDecimal ratio = netInHand.compareTo(BigDecimal.ZERO) > 0
                    ? calc.loadedCost().divide(netInHand, 6, java.math.RoundingMode.HALF_UP)
                    : null;

            SimulationResult entity = new SimulationResult();
            entity.setPaysId(req.paysId());
            entity.setProfileUserId(entry.profileUserId());
            entity.setParameterSetId(ps.getId());
            entity.setSimulationType("COHORT");
            entity.setContractType(contractType);
            entity.setMode(mode.name());
            entity.setInputNet(netInHand);
            entity.setNetTaxable(calc.netTaxable());
            entity.setTaxableBase(calc.taxableBase());
            entity.setGross(calc.gross());
            entity.setGrossWithBenefits(grossWithBen);
            entity.setCostNetRatio(ratio);
            entity.setLoadedCost(calc.loadedCost());
            entity.setLocalCurrency(fxService.localCurrency(req.paysId()));
            entity.setFxRateEur(fxService.eurRate(req.paysId()));
            entity.setFxRateUsd(fxService.usdRate(req.paysId()));
            entity.setFxRateChf(fxService.chfRate(req.paysId()));
            entity.setLoadedCostEur(fxService.convertToEur(calc.loadedCost(), req.paysId()));
            entity.setLoadedCostUsd(fxService.convertToUsd(calc.loadedCost(), req.paysId()));
            entity.setLoadedCostChf(fxService.convertToChf(calc.loadedCost(), req.paysId()));
            entity.setIrppAmount(calc.irppAmount());
            entity.setEmployeeCharges(calc.employeeCharges());
            entity.setEmployerCharges(calc.employerCharges());
            entity.setBenefitsApplied(serializeBenefits(benefits));
            entity.setRubriquesApplied(serializeRubriques(calc.evaluatedRubriques()));
            entity.setIterationsUsed(calc.iterationsUsed());
            entity.setConvergenceOk(calc.convergenceOk());
            entity.setCohortId(cohort.getId());
            entity.setSimulatedBy(simulatedBy);

            results.add(entity);
            totalLoadedCost = totalLoadedCost.add(calc.loadedCost());
        }

        List<SimulationResult> saved = resultRepo.saveAll(results);

        cohort.setTotalLoadedCost(totalLoadedCost);
        cohort.setTotalHeadcount(req.employees().size());
        cohort.setStatus("VALIDATED");
        cohortRepo.save(cohort);

        return saved.stream().map(this::toDto).toList();
    }

    public List<SimulationResultDto> getByCohort(Long cohortId) {
        return resultRepo.findByCohortId(cohortId).stream().map(this::toDto).toList();
    }

    private CohortDefinition createCohort(CohortSimulationRequest req, Long paramSetId, Long createdBy) {
        CohortDefinition c = new CohortDefinition();
        c.setPaysId(req.paysId());
        c.setName(req.cohortName() != null ? req.cohortName()
                : "Cohort " + req.paysId() + "/" + req.fiscalYear());
        c.setFiscalYear(req.fiscalYear());
        c.setParameterSetId(paramSetId);
        c.setCreatedBy(createdBy);
        return cohortRepo.save(c);
    }

    private String serializeBenefits(List<BenefitCatalogue> benefits) {
        try {
            return objectMapper.writeValueAsString(benefits.stream()
                    .map(b -> Map.of("code", b.getBenefitCode(),
                            "monthly", b.getMonthlyValue(),
                            "taxable", b.getIsTaxable()))
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private String serializeRubriques(List<TopologicalEvaluator.EvaluatedRubrique> evaluatedRubriques) {
        try {
            return objectMapper.writeValueAsString(evaluatedRubriques.stream()
                    .map(er -> {
                        PayrollRubrique r = er.rubrique();
                        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
                        m.put("code",      r.getCode());
                        m.put("nature",    r.getNature());
                        m.put("calcMode",  r.getCalcMode());
                        m.put("direction", r.getDirection());
                        m.put("amount",    er.amount());
                        return m;
                    })
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private SimulationResultDto toDto(SimulationResult e) {
        return new SimulationResultDto(
                e.getId(), e.getPaysId(), e.getProfileUserId(), e.getParameterSetId(),
                e.getSimulationType(), e.getContractType(),
                e.getInputNet(), e.getNetTaxable(), e.getTaxableBase(),
                e.getGross(), e.getGrossWithBenefits(), e.getLoadedCost(),
                e.getLoadedCostEur(), e.getLoadedCostUsd(), e.getLoadedCostChf(),
                e.getFxRateEur(), e.getFxRateUsd(), e.getFxRateChf(),
                e.getLocalCurrency(), e.getCostNetRatio(),
                e.getIrppAmount(), e.getEmployeeCharges(), e.getEmployerCharges(),
                e.getBenefitsApplied(), e.getRubriquesApplied(),
                e.getIterationsUsed(), e.getConvergenceOk(),
                e.getCohortId(),
                e.getCandidateLabel(), e.getPoste(), e.getGrade(), e.getDiscipline(),
                e.getMode(),
                e.getSimulatedAt());
    }
}
