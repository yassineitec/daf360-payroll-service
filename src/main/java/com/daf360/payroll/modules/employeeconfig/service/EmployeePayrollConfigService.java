package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.engine.FxSnapshotService;
import com.daf360.payroll.modules.employeeconfig.dto.CalculateNetRequest;
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
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.orchestrator.PayrollOrchestrator;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.FxEntryConversionService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-employee payroll configuration — the first per-EMPLOYEE (not per-country) scoping
 * anywhere in this service. See
 * docs/superpowers/specs/2026-09-22-employee-payroll-config-design.md and
 * docs/superpowers/specs/2026-09-23-employee-config-enhancements-design.md.
 *
 * <p>Rules since the 2026-11-10 Rémunération audit:
 * <ul>
 *   <li>The net is computed by the REAL payroll engine ({@link PayrollOrchestrator#preview}),
 *       not the old simulator — it is the net the payslip will show. On save it is always
 *       recomputed server-side from the saved gross, so a stored gross and net can no longer
 *       disagree.</li>
 *   <li>Family situation comes from the RH profile ({@link EmployeeFamilyResolver}); the
 *       request no longer carries it.</li>
 *   <li>The saved net is mirrored into RH's {@code salaire_net_rh} (certificates,
 *       offboarding, Cohort).</li>
 *   <li>Benefits-in-kind stay informational: they do not enter the engine's net.</li>
 * </ul>
 */
@Slf4j
@Service
public class EmployeePayrollConfigService {

    /** Working days used for the preview — same default as a payroll run. */
    private static final BigDecimal PREVIEW_WORKING_DAYS = BigDecimal.valueOf(22);

    private final EmployeePayrollConfigRepository repo;
    private final EmployeePayrollConfigHistoryRepository historyRepo;
    private final HrEmployeeClient hrEmployeeClient;
    private final EmployeeFamilyResolver familyResolver;
    private final ObjectMapper objectMapper;
    private final ParameterSetService paramSetService;
    private final PayrollOrchestrator orchestrator;
    private final FxSnapshotService fxService;
    private final FxEntryConversionService fxEntryService;
    private final UsersRefRepository usersRefRepo;

    public EmployeePayrollConfigService(EmployeePayrollConfigRepository repo,
                                         EmployeePayrollConfigHistoryRepository historyRepo,
                                         HrEmployeeClient hrEmployeeClient,
                                         EmployeeFamilyResolver familyResolver,
                                         ObjectMapper objectMapper,
                                         ParameterSetService paramSetService,
                                         PayrollOrchestrator orchestrator,
                                         FxSnapshotService fxService,
                                         FxEntryConversionService fxEntryService,
                                         UsersRefRepository usersRefRepo) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.hrEmployeeClient = hrEmployeeClient;
        this.familyResolver = familyResolver;
        this.objectMapper = objectMapper;
        this.paramSetService = paramSetService;
        this.orchestrator = orchestrator;
        this.fxService = fxService;
        this.fxEntryService = fxEntryService;
        this.usersRefRepo = usersRefRepo;
    }

    /**
     * Existing config if one was ever saved, otherwise a not-yet-persisted default — never an
     * error. "Not configured yet" is a normal, expected first-time state, so this always
     * returns 200: it best-effort pre-fills paysId/contractType from the employee's own RH
     * profile via HrEmployeeClient, and falls back to a bare template (null paysId, "CDI")
     * when RH is unreachable. Family situation is always the RH one when RH answers.
     */
    public EmployeePayrollConfigDto getOrDefault(Long profileUserId) {
        Optional<EmployeePayrollConfig> existing = repo.findByProfileUserId(profileUserId);
        Optional<HrEmployeeClient.HrEmployeeDto> hr = hrEmployeeClient.findEmployeeByUserId(profileUserId);
        if (existing.isPresent()) {
            return toDto(existing.get(), familyResolver.resolve(hr, existing.get()));
        }

        Long paysId = hr.map(HrEmployeeClient.HrEmployeeDto::paysId).orElse(null);
        String contractType = ContractTypeCodes.normalize(
                hr.map(HrEmployeeClient.HrEmployeeDto::contractType).orElse(null));
        String workingCurrency = paysId == null ? null : fxService.resolve(paysId).currency();
        EmployeeFamilyResolver.FamilySituation family = familyResolver.resolve(hr, null);
        return new EmployeePayrollConfigDto(profileUserId, paysId, contractType, List.of(), null, null,
                family.married(), family.childrenCount(), null, null, null, null, workingCurrency,
                family.fromHr(), null);
    }

    /**
     * Net for a given gross, by the real payroll engine, WITHOUT saving anything. Uses the
     * country/contract the screen is currently showing when the caller sends them (they may
     * not be saved yet), otherwise the saved/default ones.
     */
    public CalculateNetResponse calculateNet(Long profileUserId, CalculateNetRequest req) {
        Long paysId = req.paysId();
        String contract = req.contractType();
        if (paysId == null || contract == null || contract.isBlank()) {
            EmployeePayrollConfigDto cfg = getOrDefault(profileUserId);
            if (paysId == null) paysId = cfg.paysId();
            if (contract == null || contract.isBlank()) contract = cfg.contractType();
        }
        if (paysId == null) {
            throw new IllegalArgumentException(
                    "Impossible de calculer le net : aucun pays configuré pour cet employé.");
        }
        BigDecimal gross = toWorkingCurrency(paysId, req.grossSalary(), req.inputCurrency()).amount();
        return new CalculateNetResponse(previewNet(profileUserId, paysId, contract, gross));
    }

    /** Contrat de la configuration paie ENREGISTRÉE, par collaborateur — pour la liste
     *  /payroll/employee-config et les écrans de calcul, qui sinon n'affichent que le contrat
     *  de la fiche RH. Les collaborateurs sans configuration enregistrée sont absents de la map. */
    public java.util.Map<Long, String> getSavedContractTypes(java.util.Collection<Long> profileUserIds) {
        if (profileUserIds == null || profileUserIds.isEmpty()) return java.util.Map.of();
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (EmployeePayrollConfig c : repo.findByProfileUserIdIn(profileUserIds)) {
            if (c.getContractType() != null) out.put(c.getProfileUserId(), ContractTypeCodes.normalize(c.getContractType()));
        }
        return out;
    }

    /**
     * Salary changes, newest first, each with the values it replaced (the next-older row),
     * the name of who saved it (users_ref) and the benefits priced as they were at save time.
     */
    public List<EmployeePayrollConfigHistoryDto> getHistory(Long profileUserId) {
        List<EmployeePayrollConfigHistory> rows = historyRepo.findByProfileUserIdOrderByChangedAtDesc(profileUserId);
        if (rows.isEmpty()) return List.of();

        Map<Long, String> names = new HashMap<>();
        Set<Long> userIds = new HashSet<>();
        for (EmployeePayrollConfigHistory h : rows) {
            if (h.getChangedBy() != null) userIds.add(h.getChangedBy());
            if (h.getApprovedBy() != null) userIds.add(h.getApprovedBy());
        }
        for (UsersRef u : usersRefRepo.findAllById(userIds)) names.put(u.getId(), u.getFullName());

        Map<Long, String> currencies = new HashMap<>();
        List<EmployeePayrollConfigHistoryDto> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            EmployeePayrollConfigHistory h = rows.get(i);
            EmployeePayrollConfigHistory previous = i + 1 < rows.size() ? rows.get(i + 1) : null;
            String currency = h.getPaysId() == null ? null
                    : currencies.computeIfAbsent(h.getPaysId(), p -> fxService.resolve(p).currency());
            List<String> codes = fromJson(h.getSelectedBenefitCodes());
            out.add(new EmployeePayrollConfigHistoryDto(
                    h.getId(), h.getPaysId(), h.getContractType(), codes, benefitsOf(h, codes),
                    h.getCurrentGrossSalary(), h.getCurrentNetSalary(),
                    previous == null ? null : previous.getCurrentGrossSalary(),
                    previous == null ? null : previous.getCurrentNetSalary(),
                    currency, h.getReason(), h.getChangedBy(), names.get(h.getChangedBy()), h.getChangedAt(),
                    h.getEffectiveDate(), h.getInputCurrency(), h.getInputAmountOriginal(),
                    h.getApprovedBy(), h.getApprovedBy() == null ? null : names.get(h.getApprovedBy()), h.getApprovedAt()));
        }
        return out;
    }

    /** Who approved a change applied through {@link #applyApproved}. */
    public record Approval(Long requestId, Long approvedBy, OffsetDateTime approvedAt) {}

    /**
     * Direct save (PUT). Since V38 a change of salary, contract or benefits on an employee who
     * already HAS a saved salary is refused here: it must be submitted for approval
     * ({@link SalaryChangeRequestService}). The first salary of an employee is saved directly —
     * a new hire's figure was already approved as a candidate cost.
     */
    public EmployeePayrollConfigDto upsert(Long profileUserId, UpsertEmployeePayrollConfigRequest req, Long actingUserId) {
        repo.findByProfileUserId(profileUserId).ifPresent(existing -> {
            if (existing.getUpdatedAt() != null && existing.getCurrentGrossSalary() != null
                    && changesSalary(existing, req)) {
                throw new IllegalStateException("Ce collaborateur a déjà une rémunération : une modification du "
                        + "salaire, du contrat ou des avantages doit être soumise à approbation (« Soumettre à approbation »).");
            }
        });
        return doUpsert(profileUserId, req, actingUserId, null);
    }

    /** Applies a change the country director approved — same code path as a save, no guard. */
    public EmployeePayrollConfigDto applyApproved(Long profileUserId, UpsertEmployeePayrollConfigRequest req,
                                                  Long submittedBy, Approval approval) {
        return doUpsert(profileUserId, req, submittedBy, approval);
    }

    /** Does this request change what the employee is paid (gross, contract, benefits)? */
    boolean changesSalary(EmployeePayrollConfig existing, UpsertEmployeePayrollConfigRequest req) {
        if (!ContractTypeCodes.normalize(existing.getContractType()).equals(ContractTypeCodes.normalize(req.contractType()))) return true;
        if (!java.util.Objects.equals(existing.getPaysId(), req.paysId())) return true;
        java.util.Set<String> before = new java.util.HashSet<>(fromJson(existing.getSelectedBenefitCodes()));
        java.util.Set<String> after = new java.util.HashSet<>(req.selectedBenefitCodes() != null ? req.selectedBenefitCodes() : List.of());
        if (!before.equals(after)) return true;
        if (req.currentGrossSalary() == null) return false;
        BigDecimal gross = isStaleConvertedEcho(existing, req)
                ? existing.getCurrentGrossSalary()
                : toWorkingCurrency(req.paysId(), req.currentGrossSalary(), req.inputCurrency()).amount();
        return gross == null || gross.compareTo(existing.getCurrentGrossSalary()) != 0;
    }

    private EmployeePayrollConfigDto doUpsert(Long profileUserId, UpsertEmployeePayrollConfigRequest req,
                                              Long actingUserId, Approval approval) {
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate effectiveDate = req.effectiveDate() != null ? req.effectiveDate() : now.toLocalDate();
        String benefitCodesJson = toJson(req.selectedBenefitCodes());
        String contract = ContractTypeCodes.normalize(req.contractType());
        requirePricedContract(req.paysId(), contract);

        Optional<EmployeePayrollConfig> found = repo.findByProfileUserId(profileUserId);
        EmployeePayrollConfig entity = found.orElseGet(() -> {
            EmployeePayrollConfig created = new EmployeePayrollConfig();
            created.setProfileUserId(profileUserId);
            created.setCreatedAt(now);
            return created;
        });

        Converted gross = isStaleConvertedEcho(found.orElse(null), req)
                // An older client showed the STORED (already converted) gross next to the
                // original currency and sent that back: converting it again would multiply
                // the salary by the exchange rate on every save. Keep what is stored.
                ? new Converted(entity.getCurrentGrossSalary(), entity.getInputCurrency(), entity.getInputAmountOriginal())
                : toWorkingCurrency(req.paysId(), req.currentGrossSalary(), req.inputCurrency());

        // The net is the engine's, from the gross being saved — never a client-side value that
        // may have been computed with other inputs. Kept from the request only when the engine
        // cannot compute (no gross, or country not set up in the engine).
        BigDecimal net = req.currentNetSalary();
        if (gross.amount() != null && gross.amount().signum() > 0) {
            try {
                net = previewNet(profileUserId, req.paysId(), contract, gross.amount());
            } catch (RuntimeException ex) {
                log.warn("upsert: net non recalculé pour l'employé {} ({}), valeur transmise conservée",
                        profileUserId, ex.getMessage());
            }
        }

        EmployeeFamilyResolver.FamilySituation family = familyResolver.resolve(profileUserId, found.orElse(null));
        if (family.fromHr()) {
            entity.setIsMarried(family.married());
            entity.setChildrenCount(family.childrenCount());
        }

        entity.setPaysId(req.paysId());
        entity.setContractType(contract);
        entity.setSelectedBenefitCodes(benefitCodesJson);
        entity.setCurrentGrossSalary(gross.amount());
        entity.setCurrentNetSalary(net);
        entity.setInputCurrency(gross.inputCurrency());
        entity.setInputAmountOriginal(gross.inputAmount());
        entity.setEffectiveDate(effectiveDate);
        entity.setUpdatedBy(actingUserId);
        entity.setUpdatedAt(now);
        entity = repo.save(entity);

        EmployeePayrollConfigHistory history = new EmployeePayrollConfigHistory();
        history.setProfileUserId(profileUserId);
        history.setPaysId(req.paysId());
        history.setContractType(contract);
        history.setSelectedBenefitCodes(benefitCodesJson);
        history.setCurrentGrossSalary(gross.amount());
        history.setCurrentNetSalary(net);
        history.setReason(req.reason());
        history.setChangedBy(actingUserId);
        history.setChangedAt(now);
        history.setInputCurrency(gross.inputCurrency());
        history.setInputAmountOriginal(gross.inputAmount());
        history.setEffectiveDate(effectiveDate);
        history.setBenefitsSnapshot(benefitsSnapshotJson(req.paysId(), req.selectedBenefitCodes()));
        if (approval != null) {
            history.setApprovedBy(approval.approvedBy());
            history.setApprovedAt(approval.approvedAt());
            history.setSalaryChangeRequestId(approval.requestId());
        }
        historyRepo.save(history);

        if (net != null) hrEmployeeClient.pushCurrentNetSalary(profileUserId, net);

        return toDto(entity, family);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** An amount typed in {@code currency} (null = working currency), in the country's working
     *  currency — the same conversion as a save. Used by the Rémunération simulation preview. */
    public BigDecimal toWorkingCurrencyAmount(Long paysId, BigDecimal typed, String currency) {
        return toWorkingCurrency(paysId, typed, currency).amount();
    }

    /** Gross in the country's working currency, plus what to store about the typed entry
     *  (both null when it was typed in the working/native currency). */
    private record Converted(BigDecimal amount, String inputCurrency, BigDecimal inputAmount) {}

    private Converted toWorkingCurrency(Long paysId, BigDecimal typed, String inputCurrency) {
        if (typed == null || inputCurrency == null || inputCurrency.isBlank()) return new Converted(typed, null, null);
        String code = inputCurrency.trim().toUpperCase(java.util.Locale.ROOT);
        String working = fxService.resolve(paysId).currency();
        boolean foreign = !code.equalsIgnoreCase(working) && !fxEntryService.isNativeCurrency(paysId, code);
        if (!foreign) return new Converted(typed, null, null);
        return new Converted(fxEntryService.convert(typed, code, working), code, typed);
    }

    /** The stored converted gross sent back with the stored input currency (and it is not
     *  also the originally typed amount) — the double-conversion signature. */
    private static boolean isStaleConvertedEcho(EmployeePayrollConfig existing, UpsertEmployeePayrollConfigRequest req) {
        if (existing == null || req.currentGrossSalary() == null || req.inputCurrency() == null) return false;
        if (existing.getInputCurrency() == null || existing.getCurrentGrossSalary() == null
                || existing.getInputAmountOriginal() == null) return false;
        return existing.getInputCurrency().equalsIgnoreCase(req.inputCurrency())
                && java.util.Objects.equals(existing.getPaysId(), req.paysId())
                && existing.getCurrentGrossSalary().compareTo(req.currentGrossSalary()) == 0
                && existing.getInputAmountOriginal().compareTo(req.currentGrossSalary()) != 0;
    }

    private BigDecimal previewNet(Long profileUserId, Long paysId, String contract, BigDecimal grossInWorkingCurrency) {
        YearMonth period = YearMonth.now();
        RunPayrollRequest run = new RunPayrollRequest();
        run.setEmployeeId(profileUserId);
        run.setPaysId(paysId);
        run.setPeriodYear(period.getYear());
        run.setPeriodMonth(period.getMonthValue());
        run.setContractTypeCode(ContractTypeCodes.normalize(contract));
        run.setJoursOuvresMois(PREVIEW_WORKING_DAYS);
        run.setTriggeredBy("employee-config-preview");
        return orchestrator.preview(run, grossInWorkingCurrency).getAggregateNet();
    }

    /**
     * Same rule the payroll screen already applied client-side: a contract the country's
     * active payroll rules don't price (no social_charge_rates row) cannot be saved — it would
     * give a net and a payslip without charges. Skipped when the country has no active rules
     * at all (nothing to check against).
     */
    private void requirePricedContract(Long paysId, String contract) {
        ParameterSet ps;
        try {
            ps = paramSetService.loadActiveEntity(paysId);
        } catch (NoSuchElementException ex) {
            return;
        }
        Set<String> priced = new TreeSet<>();
        for (SocialChargeRate r : paramSetService.loadRates(ps.getId())) {
            if (r.getContractType() != null && !r.getContractType().isBlank()) {
                priced.add(ContractTypeCodes.normalize(r.getContractType()));
            }
        }
        if (!priced.isEmpty() && !priced.contains(contract)) {
            throw new IllegalArgumentException("Le contrat « " + contract
                    + " » n'a pas de règles de paie dans ce pays (contrats tarifés : "
                    + String.join(", ", priced) + "). Ajoutez-les dans les règles de paie, ou corrigez le contrat.");
        }
    }

    private EmployeePayrollConfigDto toDto(EmployeePayrollConfig e, EmployeeFamilyResolver.FamilySituation family) {
        String workingCurrency = e.getPaysId() == null ? null : fxService.resolve(e.getPaysId()).currency();
        return new EmployeePayrollConfigDto(
                e.getProfileUserId(), e.getPaysId(), e.getContractType(),
                fromJson(e.getSelectedBenefitCodes()), e.getCurrentGrossSalary(), e.getCurrentNetSalary(),
                family.married(), family.childrenCount(),
                e.getUpdatedBy(), e.getUpdatedAt(),
                e.getInputCurrency(), e.getInputAmountOriginal(), workingCurrency,
                family.fromHr(), e.getEffectiveDate());
    }

    /**
     * Selected benefits priced from the country's ACTIVE rules, as JSON for the history row —
     * so the history keeps showing what they were worth that day. Null when nothing is
     * selected; codes alone (no amount) when the country has no active rules.
     */
    private String benefitsSnapshotJson(Long paysId, List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        Map<String, BenefitCatalogue> catalogue = new HashMap<>();
        try {
            ParameterSet ps = paramSetService.loadActiveEntity(paysId);
            for (BenefitCatalogue b : paramSetService.loadBenefits(ps.getId())) catalogue.put(b.getBenefitCode(), b);
        } catch (RuntimeException ex) {
            log.warn("upsert: avantages non valorisés pour le pays {} ({})", paysId, ex.getMessage());
        }
        List<EmployeePayrollConfigHistoryDto.BenefitSnapshot> snapshot = codes.stream().map(code -> {
            BenefitCatalogue b = catalogue.get(code);
            return b == null
                    ? new EmployeePayrollConfigHistoryDto.BenefitSnapshot(code, null, null, null, null)
                    : new EmployeePayrollConfigHistoryDto.BenefitSnapshot(code, b.getBenefitLabelFr(),
                            b.getBenefitLabelEn(), b.getMonthlyValue(), b.getIsTaxable());
        }).toList();
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return null;
        }
    }

    /** The row's snapshot when it has one (V37+), otherwise its codes without amounts. */
    private List<EmployeePayrollConfigHistoryDto.BenefitSnapshot> benefitsOf(EmployeePayrollConfigHistory h, List<String> codes) {
        if (h.getBenefitsSnapshot() != null && !h.getBenefitsSnapshot().isBlank()) {
            try {
                return objectMapper.readValue(h.getBenefitsSnapshot(), new TypeReference<>() {});
            } catch (Exception ignored) {
                // fall through to the codes
            }
        }
        return codes.stream()
                .map(c -> new EmployeePayrollConfigHistoryDto.BenefitSnapshot(c, null, null, null, null))
                .toList();
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
