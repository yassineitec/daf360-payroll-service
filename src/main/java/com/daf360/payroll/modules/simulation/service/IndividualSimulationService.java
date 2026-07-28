package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationResultDto;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class IndividualSimulationService {

    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;
    private final FxSnapshotService fxService;
    private final SimulationResultRepository resultRepo;
    private final ObjectMapper objectMapper;

    public IndividualSimulationService(ParameterSetService paramSetService,
                                       PayrollSimulatorService simulatorService,
                                       FxSnapshotService fxService,
                                       SimulationResultRepository resultRepo,
                                       ObjectMapper objectMapper) {
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
        this.fxService = fxService;
        this.resultRepo = resultRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SimulationResultDto simulate(SimulationRequest req, Long simulatedBy) {
        ParameterSet ps = paramSetService.loadActiveEntity(req.paysId());
        List<SocialChargeRate> rates = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> benefits = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique> rubriques = paramSetService.loadRubriques(ps.getId());

        String contractType = req.contractType() != null ? req.contractType() : "CDI";
        int joursTravailes = req.joursTravailes() != null ? req.joursTravailes() : 22;

        PayrollSimulatorService.PayrollResult result =
                simulatorService.computeFromNet(req.inputNet(), ps, rates, benefits, rubriques, contractType, joursTravailes);

        SimulationResult entity = new SimulationResult();
        entity.setPaysId(req.paysId());
        entity.setProfileUserId(req.profileUserId());
        entity.setParameterSetId(ps.getId());
        entity.setSimulationType("INDIVIDUAL");
        entity.setContractType(contractType);
        entity.setInputNet(req.inputNet());
        entity.setNetTaxable(result.netTaxable());
        entity.setTaxableBase(result.taxableBase());
        entity.setGross(result.gross());
        entity.setLoadedCost(result.loadedCost());
        entity.setLocalCurrency(fxService.localCurrency(req.paysId()));
        entity.setFxRateEur(fxService.eurRate(req.paysId()));
        entity.setFxRateUsd(fxService.usdRate(req.paysId()));
        entity.setLoadedCostEur(fxService.convertToEur(result.loadedCost(), req.paysId()));
        entity.setLoadedCostUsd(fxService.convertToUsd(result.loadedCost(), req.paysId()));
        entity.setIrppAmount(result.irppAmount());
        entity.setEmployeeCharges(result.employeeCharges());
        entity.setEmployerCharges(result.employerCharges());
        entity.setBenefitsApplied(serializeBenefits(benefits));
        entity.setRubriquesApplied(serializeRubriques(rubriques));
        entity.setIterationsUsed(result.iterationsUsed());
        entity.setConvergenceOk(result.convergenceOk());
        entity.setSimulatedBy(simulatedBy);

        entity = resultRepo.save(entity);
        return toDto(entity);
    }

    public List<SimulationResultDto> history(Long paysId) {
        return resultRepo.findByPaysIdOrderBySimulatedAtDesc(paysId).stream()
                .map(this::toDto)
                .toList();
    }

    private String serializeBenefits(List<BenefitCatalogue> benefits) {
        try {
            return objectMapper.writeValueAsString(benefits.stream()
                    .map(b -> Map.of(
                            "code", b.getBenefitCode(),
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
