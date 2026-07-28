package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.dto.CohortSimulationRequest;
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

        List<SimulationResult> results = new ArrayList<>();
        BigDecimal totalLoadedCost = BigDecimal.ZERO;

        for (CohortSimulationRequest.EmployeeSimEntry entry : req.employees()) {
            String contractType = entry.contractType() != null ? entry.contractType() : "CDI";

            PayrollSimulatorService.PayrollResult calc =
                    simulatorService.computeFromNet(entry.inputNet(), ps, rates, benefits, rubriques, contractType, 22);

            SimulationResult entity = new SimulationResult();
            entity.setPaysId(req.paysId());
            entity.setProfileUserId(entry.profileUserId());
            entity.setParameterSetId(ps.getId());
            entity.setSimulationType("COHORT");
            entity.setContractType(contractType);
            entity.setInputNet(entry.inputNet());
            entity.setNetTaxable(calc.netTaxable());
            entity.setTaxableBase(calc.taxableBase());
            entity.setGross(calc.gross());
            entity.setLoadedCost(calc.loadedCost());
            entity.setLocalCurrency(fxService.localCurrency(req.paysId()));
            entity.setFxRateEur(fxService.eurRate(req.paysId()));
            entity.setFxRateUsd(fxService.usdRate(req.paysId()));
            entity.setLoadedCostEur(fxService.convertToEur(calc.loadedCost(), req.paysId()));
            entity.setLoadedCostUsd(fxService.convertToUsd(calc.loadedCost(), req.paysId()));
            entity.setIrppAmount(calc.irppAmount());
            entity.setEmployeeCharges(calc.employeeCharges());
            entity.setEmployerCharges(calc.employerCharges());
            entity.setBenefitsApplied(serializeBenefits(benefits));
            entity.setRubriquesApplied(serializeRubriques(rubriques));
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

    private String serializeRubriques(List<PayrollRubrique> rubriques) {
        try {
            return objectMapper.writeValueAsString(rubriques.stream()
                    .map(r -> Map.of(
                            "code", r.getCode(),
                            "nature", r.getNature(),
                            "calcMode", r.getCalcMode(),
                            "direction", r.getDirection()))
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private SimulationResultDto toDto(SimulationResult e) {
        return new SimulationResultDto(
                e.getId(), e.getPaysId(), e.getProfileUserId(), e.getParameterSetId(),
                e.getSimulationType(), e.getContractType(), e.getInputNet(),
                e.getNetTaxable(), e.getTaxableBase(), e.getGross(), e.getLoadedCost(),
                e.getLoadedCostEur(), e.getLoadedCostUsd(),
                e.getFxRateEur(), e.getFxRateUsd(),
                e.getLocalCurrency(),
                e.getIrppAmount(), e.getEmployeeCharges(), e.getEmployerCharges(),
                e.getBenefitsApplied(), e.getRubriquesApplied(),
                e.getIterationsUsed(), e.getConvergenceOk(),
                e.getCohortId(), e.getSimulatedAt());
    }
}
