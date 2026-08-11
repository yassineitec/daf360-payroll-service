package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient.HrEmployeeDto;
import com.daf360.payroll.modules.simulation.dto.SimulationMode;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationResultDto;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class IndividualSimulationService {

    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;
    private final FxSnapshotService fxService;
    private final SimulationResultRepository resultRepo;
    private final ObjectMapper objectMapper;
    private final HrEmployeeClient hrClient;

    public IndividualSimulationService(ParameterSetService paramSetService,
                                       PayrollSimulatorService simulatorService,
                                       FxSnapshotService fxService,
                                       SimulationResultRepository resultRepo,
                                       ObjectMapper objectMapper,
                                       HrEmployeeClient hrClient) {
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
        this.fxService = fxService;
        this.resultRepo = resultRepo;
        this.objectMapper = objectMapper;
        this.hrClient = hrClient;
    }

    @Transactional
    public SimulationResultDto simulate(SimulationRequest req, Long simulatedBy) {

        // C4 — profile hydration: if profileUserId is set, pull contractType/grade/discipline
        // from the HR service so callers don't have to repeat data already on the employee card.
        // HR service unavailability is non-fatal — we fall back to request values.
        Optional<HrEmployeeDto> profile = Optional.empty();
        if (req.profileUserId() != null) {
            profile = hrClient.findEmployeeByUserId(req.profileUserId());
            if (profile.isEmpty()) {
                log.warn("simulate: HR profile not found for profileUserId={}, proceeding with request values",
                        req.profileUserId());
            }
        }

        String contractType = firstNonBlank(req.contractType(),
                profile.map(HrEmployeeDto::contractType).orElse(null),
                "CDI");
        int joursTravailes = req.joursTravailes() != null ? req.joursTravailes() : 22;

        // Grade / discipline: prefer explicit request values; fall back to HR profile
        String grade      = firstNonBlank(req.grade(),      profile.map(HrEmployeeDto::grade).orElse(null));
        String discipline = firstNonBlank(req.discipline(),  profile.map(HrEmployeeDto::discipline).orElse(null));

        ParameterSet ps = paramSetService.loadActiveEntity(req.paysId());
        List<SocialChargeRate> rates = paramSetService.loadRates(ps.getId());
        List<BenefitCatalogue> allBenefits = paramSetService.loadBenefits(ps.getId());
        List<PayrollRubrique> rubriques = paramSetService.loadRubriques(ps.getId());

        // Filter benefits by selectedBenefitCodes (null = all; empty list = none)
        List<BenefitCatalogue> benefits = (req.selectedBenefitCodes() == null)
                ? allBenefits
                : allBenefits.stream()
                        .filter(b -> req.selectedBenefitCodes().contains(b.getBenefitCode()))
                        .toList();

        // ── Mode branch ──────────────────────────────────────────────────────────
        SimulationMode mode = req.mode() != null ? req.mode() : SimulationMode.NET_TO_BRUT;

        PayrollSimulatorService.PayrollResult result;
        if (mode == SimulationMode.BRUT_TO_NET) {
            if (req.inputGross() == null || req.inputGross().compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputGross est requis et doit être positif en mode BRUT_TO_NET.");
            result = simulatorService.computeFromGross(
                    req.inputGross(), ps, rates, benefits, rubriques, contractType, joursTravailes);
        } else {
            if (req.inputNet() == null || req.inputNet().compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputNet est requis et doit être positif en mode NET_TO_BRUT.");
            result = simulatorService.computeFromNet(
                    req.inputNet(), ps, rates, benefits, rubriques, contractType, joursTravailes);
        }

        // S1 — net versé: the user's target in NET_TO_BRUT, the computed net in BRUT_TO_NET
        java.math.BigDecimal netInHand = result.netInHand();

        // S4 = gross + exempt (non-taxable) benefits
        java.math.BigDecimal exemptBenefitsTotal = benefits.stream()
                .filter(b -> Boolean.FALSE.equals(b.getIsTaxable()))
                .map(b -> b.getMonthlyValue() != null ? b.getMonthlyValue() : java.math.BigDecimal.ZERO)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal grossWithBenefits = result.gross().add(exemptBenefitsTotal)
                .setScale(4, java.math.RoundingMode.HALF_UP);

        // cost/net ratio — always against the actual net in hand
        java.math.BigDecimal costNetRatio = (netInHand.compareTo(java.math.BigDecimal.ZERO) > 0)
                ? result.loadedCost().divide(netInHand, 6, java.math.RoundingMode.HALF_UP)
                : null;

        SimulationResult entity = new SimulationResult();
        entity.setPaysId(req.paysId());
        entity.setProfileUserId(req.profileUserId());
        entity.setParameterSetId(ps.getId());
        entity.setSimulationType("INDIVIDUAL");
        entity.setContractType(contractType);
        entity.setMode(mode.name());
        // inputNet stores the actual net in hand regardless of mode
        entity.setInputNet(netInHand);
        entity.setNetTaxable(result.netTaxable());
        entity.setTaxableBase(result.taxableBase());
        entity.setGross(result.gross());
        entity.setGrossWithBenefits(grossWithBenefits);
        entity.setCostNetRatio(costNetRatio);
        entity.setLoadedCost(result.loadedCost());
        entity.setLocalCurrency(fxService.localCurrency(req.paysId()));
        entity.setFxRateEur(fxService.eurRate(req.paysId()));
        entity.setFxRateUsd(fxService.usdRate(req.paysId()));
        entity.setFxRateChf(fxService.chfRate(req.paysId()));
        entity.setLoadedCostEur(fxService.convertToEur(result.loadedCost(), req.paysId()));
        entity.setLoadedCostUsd(fxService.convertToUsd(result.loadedCost(), req.paysId()));
        entity.setLoadedCostChf(fxService.convertToChf(result.loadedCost(), req.paysId()));
        entity.setIrppAmount(result.irppAmount());
        entity.setEmployeeCharges(result.employeeCharges());
        entity.setEmployerCharges(result.employerCharges());
        entity.setBenefitsApplied(serializeBenefits(benefits));
        entity.setRubriquesApplied(serializeRubriques(result.evaluatedRubriques()));
        entity.setIterationsUsed(result.iterationsUsed());
        entity.setConvergenceOk(result.convergenceOk());
        entity.setCandidateLabel(req.candidateLabel());
        entity.setPoste(req.poste());
        entity.setGrade(grade);
        entity.setDiscipline(discipline);
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

    /** Returns the first non-null, non-blank value from the candidates, or null if none qualify. */
    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
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
