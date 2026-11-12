package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateGrossResponse;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetResponse;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigHistoryDto;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Per-employee payroll configuration — the first per-EMPLOYEE (not per-country) scoping
 * anywhere in this service. See
 * docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md and
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.
 */
@Service
public class EmployeePayrollConfigService {

    private final EmployeePayrollConfigRepository repo;
    private final EmployeePayrollConfigHistoryRepository historyRepo;
    private final HrEmployeeClient hrEmployeeClient;
    private final ObjectMapper objectMapper;
    private final ParameterSetService paramSetService;
    private final PayrollSimulatorService simulatorService;
    private final com.daf360.payroll.engine.FxSnapshotService fxService;
    private final com.daf360.payroll.modules.ref.service.FxEntryConversionService fxEntryService;

    public EmployeePayrollConfigService(EmployeePayrollConfigRepository repo,
                                         EmployeePayrollConfigHistoryRepository historyRepo,
                                         HrEmployeeClient hrEmployeeClient,
                                         ObjectMapper objectMapper,
                                         ParameterSetService paramSetService,
                                         PayrollSimulatorService simulatorService,
                                         com.daf360.payroll.engine.FxSnapshotService fxService,
                                         com.daf360.payroll.modules.ref.service.FxEntryConversionService fxEntryService) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.hrEmployeeClient = hrEmployeeClient;
        this.objectMapper = objectMapper;
        this.paramSetService = paramSetService;
        this.simulatorService = simulatorService;
        this.fxService = fxService;
        this.fxEntryService = fxEntryService;
    }

    /**
     * Existing config if one was ever saved, otherwise a not-yet-persisted default — never an
     * error. "Not configured yet" is a normal, expected first-time state, not an exceptional
     * one, so this always returns 200: it best-effort pre-fills paysId/contractType from the
     * employee's own profile via HrEmployeeClient (the same convenience-hydration client
     * IndividualSimulationService already uses) when that succeeds, and falls back to a bare
     * template (null paysId, "CDI" contractType) when it doesn't — matching this client's own
     * established "HR unavailability is non-fatal" convention. A caller with its own paysId
     * already at hand (daf360-rh-frontend, from the employee's already-loaded RH profile) can
     * simply ignore a null paysId here and use its own.
     */
    public EmployeePayrollConfigDto getOrDefault(Long profileUserId) {
        Optional<EmployeePayrollConfig> existing = repo.findByProfileUserId(profileUserId);
        if (existing.isPresent()) return toDto(existing.get());

        Optional<HrEmployeeClient.HrEmployeeDto> hr = hrEmployeeClient.findEmployeeByUserId(profileUserId);
        Long paysId = hr.map(HrEmployeeClient.HrEmployeeDto::paysId).orElse(null);
        String contractType = hr.map(HrEmployeeClient.HrEmployeeDto::contractType)
                .filter(ct -> ct != null && !ct.isBlank())
                .orElse("CDI");
        String workingCurrency = paysId == null ? null : fxService.resolve(paysId).currency();
        return new EmployeePayrollConfigDto(profileUserId, paysId, contractType, List.of(), null, null, false, 0, null, null, null, null, workingCurrency);
    }

    /**
     * Derives net salary from a given gross using the same simulation engine
     * IndividualSimulationService uses, for this employee's own configured country,
     * contract type and selected benefits. Deliberately does NOT persist a SimulationResult
     * row — that table is real simulation-run history; this is a thin, non-persisting
     * calculation used to fill the net field while editing this screen. See
     * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.
     */
    public CalculateNetResponse calculateNet(Long profileUserId, BigDecimal grossSalary, String inputCurrency) {
        EngineInputs in = engineInputs(profileUserId, "net");
        // 22 mirrors IndividualSimulationService's own joursTravailes default; this screen
        // doesn't expose an override.
        PayrollSimulatorService.PayrollResult result = simulatorService.computeFromGross(
                in.toWorking(grossSalary, inputCurrency), in.ps(), in.rates(), in.benefits(), in.rubriques(),
                in.cfg().contractType(), 22, in.cfg().isMarried(), in.cfg().childrenCount());

        return new CalculateNetResponse(result.netInHand());
    }

    /** Calcul inverse de {@link #calculateNet} : net saisi → brut, par le même moteur et la même
     *  configuration ENREGISTRÉE que le simulateur en mode « Net → Brut ». Le net est saisi dans
     *  la devise du champ (comme le brut), et le brut rendu dans cette même devise. */
    public CalculateGrossResponse calculateGross(Long profileUserId, BigDecimal netSalary, String inputCurrency) {
        EngineInputs in = engineInputs(profileUserId, "brut");
        PayrollSimulatorService.PayrollResult result = simulatorService.computeFromNet(
                in.toWorking(netSalary, inputCurrency), in.ps(), in.rates(), in.benefits(), in.rubriques(),
                in.cfg().contractType(), 22, in.cfg().isMarried(), in.cfg().childrenCount());

        BigDecimal gross = in.isConverted(inputCurrency)
                ? fxEntryService.convert(result.gross(), in.cfg().workingCurrency(), inputCurrency)
                : result.gross();
        return new CalculateGrossResponse(
                gross.setScale(2, java.math.RoundingMode.HALF_UP), result.netInHand(), result.convergenceOk());
    }

    /** Ce qu'il faut au moteur pour un collaborateur : sa config enregistrée et le jeu de
     *  paramètres actif de son pays (taux, avantages retenus, rubriques). */
    private record EngineInputs(EmployeePayrollConfigDto cfg, ParameterSet ps, List<SocialChargeRate> rates,
                                List<BenefitCatalogue> benefits, List<PayrollRubrique> rubriques,
                                com.daf360.payroll.modules.ref.service.FxEntryConversionService fx) {
        /** Une devise de saisie absente ou « native » du pays ne déclenche aucune conversion. */
        boolean isConverted(String inputCurrency) {
            return inputCurrency != null && !fx.isNativeCurrency(cfg.paysId(), inputCurrency);
        }
        /** Montant saisi → devise de travail (déjà résolue par getOrDefault()). */
        BigDecimal toWorking(BigDecimal amount, String inputCurrency) {
            return isConverted(inputCurrency) ? fx.convert(amount, inputCurrency, cfg.workingCurrency()) : amount;
        }
    }

    private EngineInputs engineInputs(Long profileUserId, String what) {
        EmployeePayrollConfigDto cfg = getOrDefault(profileUserId);
        if (cfg.paysId() == null) {
            throw new IllegalArgumentException(
                    "Impossible de calculer le " + what + " : aucun pays configuré pour cet employé.");
        }
        ParameterSet ps = paramSetService.loadActiveEntity(cfg.paysId());
        List<BenefitCatalogue> benefits = paramSetService.loadBenefits(ps.getId()).stream()
                .filter(b -> cfg.selectedBenefitCodes().contains(b.getBenefitCode()))
                .toList();
        return new EngineInputs(cfg, ps, paramSetService.loadRates(ps.getId()), benefits,
                paramSetService.loadRubriques(ps.getId()), fxEntryService);
    }

    /** Contrat de la configuration paie ENREGISTRÉE, par collaborateur — pour la liste
     *  /payroll/employee-config, qui sinon n'affiche que le contrat de la fiche RH.
     *  Les collaborateurs sans configuration enregistrée sont absents de la map. */
    public java.util.Map<Long, String> getSavedContractTypes(java.util.Collection<Long> profileUserIds) {
        if (profileUserIds == null || profileUserIds.isEmpty()) return java.util.Map.of();
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (EmployeePayrollConfig c : repo.findByProfileUserIdIn(profileUserIds)) {
            if (c.getContractType() != null) out.put(c.getProfileUserId(), c.getContractType());
        }
        return out;
    }

    public List<EmployeePayrollConfigHistoryDto> getHistory(Long profileUserId) {
        return historyRepo.findByProfileUserIdOrderByChangedAtDesc(profileUserId).stream()
                .map(this::toHistoryDto)
                .toList();
    }

    public EmployeePayrollConfigDto upsert(Long profileUserId, UpsertEmployeePayrollConfigRequest req, Long actingUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        String benefitCodesJson = toJson(req.selectedBenefitCodes());

        com.daf360.payroll.engine.FxSnapshotService.FxSnapshot fx = fxService.resolve(req.paysId());
        boolean currencyConverted = req.inputCurrency() != null
                && !req.inputCurrency().equalsIgnoreCase(fx.currency())
                && !fxEntryService.isNativeCurrency(req.paysId(), req.inputCurrency());
        BigDecimal rawGross = req.currentGrossSalary();
        BigDecimal convertedGross = (rawGross == null || !currencyConverted)
                ? rawGross
                : fxEntryService.convert(rawGross, req.inputCurrency(), fx.currency());
        String storedInputCurrency = currencyConverted ? req.inputCurrency().toUpperCase() : null;
        BigDecimal storedInputAmount = currencyConverted ? rawGross : null;

        EmployeePayrollConfig entity = repo.findByProfileUserId(profileUserId).orElseGet(() -> {
            EmployeePayrollConfig created = new EmployeePayrollConfig();
            created.setProfileUserId(profileUserId);
            created.setCreatedAt(now);
            return created;
        });
        entity.setPaysId(req.paysId());
        entity.setContractType(req.contractType());
        entity.setSelectedBenefitCodes(benefitCodesJson);
        entity.setCurrentGrossSalary(convertedGross);
        entity.setCurrentNetSalary(req.currentNetSalary());
        entity.setIsMarried(req.isMarried() != null ? req.isMarried() : false);
        entity.setChildrenCount(req.childrenCount() != null ? req.childrenCount() : 0);
        entity.setInputCurrency(storedInputCurrency);
        entity.setInputAmountOriginal(storedInputAmount);
        entity.setUpdatedBy(actingUserId);
        entity.setUpdatedAt(now);
        entity = repo.save(entity);

        EmployeePayrollConfigHistory history = new EmployeePayrollConfigHistory();
        history.setProfileUserId(profileUserId);
        history.setPaysId(req.paysId());
        history.setContractType(req.contractType());
        history.setSelectedBenefitCodes(benefitCodesJson);
        history.setCurrentGrossSalary(convertedGross);
        history.setCurrentNetSalary(req.currentNetSalary());
        history.setReason(req.reason());
        history.setChangedBy(actingUserId);
        history.setChangedAt(now);
        history.setInputCurrency(storedInputCurrency);
        history.setInputAmountOriginal(storedInputAmount);
        historyRepo.save(history);

        return toDto(entity);
    }

    private EmployeePayrollConfigDto toDto(EmployeePayrollConfig e) {
        String workingCurrency = e.getPaysId() == null ? null : fxService.resolve(e.getPaysId()).currency();
        return new EmployeePayrollConfigDto(
                e.getProfileUserId(), e.getPaysId(), e.getContractType(),
                fromJson(e.getSelectedBenefitCodes()), e.getCurrentGrossSalary(), e.getCurrentNetSalary(),
                e.getIsMarried(), e.getChildrenCount(),
                e.getUpdatedBy(), e.getUpdatedAt(),
                e.getInputCurrency(), e.getInputAmountOriginal(), workingCurrency);
    }

    private EmployeePayrollConfigHistoryDto toHistoryDto(EmployeePayrollConfigHistory h) {
        return new EmployeePayrollConfigHistoryDto(
                h.getPaysId(), h.getContractType(), fromJson(h.getSelectedBenefitCodes()),
                h.getCurrentGrossSalary(), h.getCurrentNetSalary(), h.getReason(), h.getChangedBy(), h.getChangedAt(),
                h.getInputCurrency(), h.getInputAmountOriginal());
    }

    private String toJson(List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(codes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Codes d'avantages invalides", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
