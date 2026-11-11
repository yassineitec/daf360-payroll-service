package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.ref.service.FxEntryConversionService;
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
    private final FxEntryConversionService fxEntryService;
    private final SimulationResultRepository resultRepo;
    private final ObjectMapper objectMapper;
    private final HrEmployeeClient hrClient;

    public IndividualSimulationService(ParameterSetService paramSetService,
                                       PayrollSimulatorService simulatorService,
                                       FxSnapshotService fxService,
                                       FxEntryConversionService fxEntryService,
                                       SimulationResultRepository resultRepo,
                                       ObjectMapper objectMapper,
                                       HrEmployeeClient hrClient) {
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
        this.fxService = fxService;
        this.fxEntryService = fxEntryService;
        this.resultRepo = resultRepo;
        this.objectMapper = objectMapper;
        this.hrClient = hrClient;
    }

    /**
     * One simulation's figures, before (or instead of) persisting them. {@link #simulate} saves
     * it as a {@code simulation_results} row; {@link #compute} alone backs previews that must not
     * fill the simulations history (Rémunération form, recalculated on every keystroke).
     */
    public record Computation(
            PayrollSimulatorService.PayrollResult result,
            ParameterSet parameterSet,
            List<BenefitCatalogue> benefits,
            FxSnapshotService.FxSnapshot fx,
            String contractType,
            SimulationMode mode,
            boolean currencyConverted,
            java.math.BigDecimal rawInputNet,
            java.math.BigDecimal rawInputGross,
            String grade,
            String discipline,
            Boolean isMarried,
            Integer children
    ) {}

    @Transactional
    public SimulationResultDto simulate(SimulationRequest req, Long simulatedBy) {
        Computation c = compute(req);
        PayrollSimulatorService.PayrollResult result = c.result();
        ParameterSet ps = c.parameterSet();
        List<BenefitCatalogue> benefits = c.benefits();
        FxSnapshotService.FxSnapshot fx = c.fx();
        SimulationMode mode = c.mode();
        String contractType = c.contractType();
        boolean currencyConverted = c.currencyConverted();
        java.math.BigDecimal rawInputNet = c.rawInputNet();
        java.math.BigDecimal rawInputGross = c.rawInputGross();
        String grade = c.grade();
        String discipline = c.discipline();
        return persist(req, simulatedBy, result, ps, benefits, fx, mode, contractType,
                currencyConverted, rawInputNet, rawInputGross, grade, discipline);
    }

    /** The whole calculation of {@link #simulate}, nothing written. */
    @Transactional(readOnly = true)
    public Computation compute(SimulationRequest req) {

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

        // Family situation (IRPP family deduction): for an EMPLOYEE, the RH file is the source of
        // truth — same rule as the payroll engine (EmployeeFamilyResolver). Before, the request's
        // checkbox/count won even when nobody filled them in, so a married employee with children
        // was simulated as single with none. The request values only serve a candidate, or an
        // employee whose RH file could not be read (older RH build / RH unreachable).
        Boolean isMarried = req.isMarried();
        Integer children  = req.children();
        if (profile.isPresent() && profile.get().married() != null) {
            isMarried = profile.get().married();
            children  = profile.get().childrenCount() != null ? profile.get().childrenCount() : 0;
        }

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

        // Resolved early (not just before persisting, as before): the working currency is
        // needed now, to know whether/how to convert a non-working-currency entry.
        FxSnapshotService.FxSnapshot fx = fxService.resolve(req.paysId());

        // ── Currency entry conversion ────────────────────────────────────────────
        // null/same-as-working inputCurrency: no-op, req.inputNet()/inputGross() pass through
        // unchanged -- today's behavior, exactly.
        java.math.BigDecimal rawInputNet = req.inputNet();
        java.math.BigDecimal rawInputGross = req.inputGross();
        boolean currencyConverted = req.inputCurrency() != null
                && !req.inputCurrency().equalsIgnoreCase(fx.currency())
                && !fxEntryService.isNativeCurrency(req.paysId(), req.inputCurrency());
        java.math.BigDecimal convertedInputNet = rawInputNet == null ? null
                : fxEntryService.convert(rawInputNet, req.inputCurrency(), fx.currency(), req.paysId());
        java.math.BigDecimal convertedInputGross = rawInputGross == null ? null
                : fxEntryService.convert(rawInputGross, req.inputCurrency(), fx.currency(), req.paysId());

        // ── Mode branch ──────────────────────────────────────────────────────────
        SimulationMode mode = req.mode() != null ? req.mode() : SimulationMode.NET_TO_BRUT;

        PayrollSimulatorService.PayrollResult result;
        if (mode == SimulationMode.BRUT_TO_NET) {
            if (convertedInputGross == null || convertedInputGross.compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputGross est requis et doit être positif en mode BRUT_TO_NET.");
            result = simulatorService.computeFromGross(
                    convertedInputGross, ps, rates, benefits, rubriques, contractType, joursTravailes,
                    isMarried, children);
        } else {
            if (convertedInputNet == null || convertedInputNet.compareTo(java.math.BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException(
                        "Le champ inputNet est requis et doit être positif en mode NET_TO_BRUT.");
            result = simulatorService.computeFromNet(
                    convertedInputNet, ps, rates, benefits, rubriques, contractType, joursTravailes,
                    isMarried, children);
        }

        return new Computation(result, ps, benefits, fx, contractType, mode, currencyConverted,
                rawInputNet, rawInputGross, grade, discipline, isMarried, children);
    }

    private SimulationResultDto persist(SimulationRequest req, Long simulatedBy,
                                        PayrollSimulatorService.PayrollResult result, ParameterSet ps,
                                        List<BenefitCatalogue> benefits, FxSnapshotService.FxSnapshot fx,
                                        SimulationMode mode, String contractType, boolean currencyConverted,
                                        java.math.BigDecimal rawInputNet, java.math.BigDecimal rawInputGross,
                                        String grade, String discipline) {
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
        entity.setInputCurrency(currencyConverted ? req.inputCurrency().toUpperCase() : null);
        entity.setInputAmountOriginal(currencyConverted
                ? (mode == SimulationMode.BRUT_TO_NET ? rawInputGross : rawInputNet)
                : null);
        entity.setNetTaxable(result.netTaxable());
        entity.setTaxableBase(result.taxableBase());
        entity.setGross(result.gross());
        entity.setGrossWithBenefits(grossWithBenefits);
        entity.setCostNetRatio(costNetRatio);
        entity.setLoadedCost(result.loadedCost());
        entity.setLocalCurrency(fx.currency());
        entity.setFxRateEur(fx.eur());
        entity.setFxRateUsd(fx.usd());
        entity.setFxRateChf(fx.chf());
        entity.setLoadedCostEur(fxService.convertToEur(result.loadedCost(), fx));
        entity.setLoadedCostUsd(fxService.convertToUsd(result.loadedCost(), fx));
        entity.setLoadedCostChf(fxService.convertToChf(result.loadedCost(), fx));
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

    /** Public : réutilisé par {@code SimulationHistoryService} pour la fiche par id. */
    public SimulationResultDto toDto(SimulationResult e) {
        return new SimulationResultDto(
                e.getId(), e.getPaysId(), e.getProfileUserId(), e.getParameterSetId(),
                e.getSimulationType(), e.getContractType(),
                e.getInputNet(), e.getInputCurrency(), e.getInputAmountOriginal(),
                e.getNetTaxable(), e.getTaxableBase(),
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
