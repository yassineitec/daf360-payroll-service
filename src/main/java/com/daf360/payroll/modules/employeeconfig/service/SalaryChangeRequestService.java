package com.daf360.payroll.modules.employeeconfig.service;

import com.daf360.payroll.modules.employeeconfig.dto.EmployeePayrollConfigDto;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationDto;
import com.daf360.payroll.modules.employeeconfig.dto.RemunerationSimulationRequest;
import com.daf360.payroll.modules.employeeconfig.dto.SalaryChangeDtos.SalaryChangeDto;
import com.daf360.payroll.modules.employeeconfig.dto.SalaryChangeDtos.SubmitSalaryChangeRequest;
import com.daf360.payroll.modules.employeeconfig.dto.UpsertEmployeePayrollConfigRequest;
import com.daf360.payroll.modules.employeeconfig.entity.SalaryChangeRequest;
import com.daf360.payroll.modules.employeeconfig.repository.SalaryChangeRequestRepository;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PaysScopeContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * Salary changes of an already-configured employee, under approval (V38).
 *
 * <ol>
 *   <li>RH (Rémunération tab) or payroll (employee page) submits the « Nouvelle rémunération »
 *       form: a PENDING request, frozen with the simulation the approver will see (new net,
 *       employer cost before/after). Nothing is applied. Re-submitting while PENDING updates
 *       the same request — one per employee in finance's queue.</li>
 *   <li>The country director decides in finance's approval queue ({@code APPROVE_HIRING_COST},
 *       the code already deciding candidate costs). APPROVED applies the change through
 *       {@link EmployeePayrollConfigService#applyApproved} — the same code as a save: engine net,
 *       history row (with the approver), salaire_net_rh mirrored to RH. REJECTED: nothing
 *       changes, the reason is required.</li>
 *   <li>The submitter may withdraw a PENDING request (CANCELLED).</li>
 * </ol>
 * No one decides on their own salary. Entity scope as everywhere in this service.
 */
@Slf4j
@Service
@Transactional
public class SalaryChangeRequestService {

    private final SalaryChangeRequestRepository repo;
    private final EmployeePayrollConfigService configService;
    private final RemunerationSimulationService simulationService;
    private final UsersRefRepository usersRefRepo;
    private final UserContextService userContext;
    private final ObjectMapper objectMapper;

    public SalaryChangeRequestService(SalaryChangeRequestRepository repo,
                                      EmployeePayrollConfigService configService,
                                      RemunerationSimulationService simulationService,
                                      UsersRefRepository usersRefRepo,
                                      UserContextService userContext,
                                      ObjectMapper objectMapper) {
        this.repo = repo;
        this.configService = configService;
        this.simulationService = simulationService;
        this.usersRefRepo = usersRefRepo;
        this.userContext = userContext;
        this.objectMapper = objectMapper;
    }

    // ── RH / payroll ───────────────────────────────────────────────────────

    public SalaryChangeDto submit(Long profileUserId, SubmitSalaryChangeRequest req, Long callerId) {
        EmployeePayrollConfigDto current = configService.getOrDefault(profileUserId);
        if (current.updatedAt() == null || current.currentGrossSalary() == null) {
            throw new IllegalStateException("Ce collaborateur n'a pas encore de rémunération : enregistrez-la directement "
                    + "(la première rémunération ne passe pas par l'approbation).");
        }
        String contract = ContractTypeCodes.normalize(req.contractType());
        List<String> codes = req.selectedBenefitCodes() != null ? req.selectedBenefitCodes() : List.of();

        // What the approver decides on: the new salary simulated, and the current one for the
        // cost difference (both with the same effective month, so bonuses weigh the same).
        RemunerationSimulationDto after = simulationService.simulate(profileUserId, new RemunerationSimulationRequest(
                req.grossSalary(), req.inputCurrency(), req.paysId(), contract, codes, req.effectiveDate()));
        BigDecimal costBefore = null;
        try {
            RemunerationSimulationDto before = simulationService.simulate(profileUserId, new RemunerationSimulationRequest(
                    current.currentGrossSalary(), null, current.paysId(), current.contractType(),
                    current.selectedBenefitCodes(), req.effectiveDate()));
            costBefore = before.monthly().employerCost();
        } catch (RuntimeException ex) {
            log.warn("salary change: coût actuel non simulé pour l'employé {} ({})", profileUserId, ex.getMessage());
        }

        SalaryChangeRequest r = repo.findFirstByProfileUserIdAndStatus(profileUserId, SalaryChangeRequest.PENDING)
                .orElseGet(() -> {
                    SalaryChangeRequest n = new SalaryChangeRequest();
                    n.setProfileUserId(profileUserId);
                    n.setStatus(SalaryChangeRequest.PENDING);
                    return n;
                });
        r.setPaysId(req.paysId());
        r.setContractType(contract);
        r.setSelectedBenefitCodes(toJson(codes));
        r.setGrossSalary(req.grossSalary());
        r.setInputCurrency(StringUtils.hasText(req.inputCurrency()) ? req.inputCurrency().trim().toUpperCase(Locale.ROOT) : null);
        r.setEffectiveDate(req.effectiveDate());
        r.setReason(req.reason().trim());
        r.setWorkingCurrency(after.currency());
        r.setCurrentGrossSalary(current.currentGrossSalary());
        r.setCurrentNetSalary(current.currentNetSalary());
        r.setNewGrossSalary(after.monthly().baseSalary());
        r.setNewNetSalary(after.monthly().net());
        r.setMonthlyCostBefore(costBefore);
        r.setMonthlyCostAfter(after.monthly().employerCost());
        r.setSimulationSnapshot(toJson(after));
        r.setSubmittedBy(callerId);
        r.setSubmittedAt(OffsetDateTime.now());
        return toDto(repo.save(r), current.contractType());
    }

    @Transactional(readOnly = true)
    public List<SalaryChangeDto> listForEmployee(Long profileUserId) {
        return toDtos(repo.findByProfileUserIdOrderBySubmittedAtDesc(profileUserId));
    }

    public SalaryChangeDto cancel(Long id, String notes, Long callerId) {
        SalaryChangeRequest r = requirePending(loadInScope(id));
        r.setStatus(SalaryChangeRequest.CANCELLED);
        r.setDecidedBy(callerId);
        r.setDecidedAt(OffsetDateTime.now());
        r.setDecisionNotes(trimToNull(notes));
        return toDto(repo.save(r), null);
    }

    // ── Country director (finance's approval queue) ─────────────────────────

    @Transactional(readOnly = true)
    public List<SalaryChangeDto> listPending() {
        return toDtos(repo.findByStatusOrderBySubmittedAtAsc(SalaryChangeRequest.PENDING).stream().filter(inScope()).toList());
    }

    public SalaryChangeDto approve(Long id, String notes, Long callerId) {
        SalaryChangeRequest r = requirePending(loadInScope(id));
        assertNotOwn(r, callerId);
        OffsetDateTime now = OffsetDateTime.now();
        configService.applyApproved(r.getProfileUserId(), new UpsertEmployeePayrollConfigRequest(
                        r.getPaysId(), r.getContractType(), fromJson(r.getSelectedBenefitCodes()),
                        r.getGrossSalary(), r.getNewNetSalary(), r.getReason(), r.getInputCurrency(), r.getEffectiveDate()),
                r.getSubmittedBy(), new EmployeePayrollConfigService.Approval(r.getId(), callerId, now));
        r.setStatus(SalaryChangeRequest.APPROVED);
        r.setDecidedBy(callerId);
        r.setDecidedAt(now);
        r.setDecisionNotes(trimToNull(notes));
        return toDto(repo.save(r), null);
    }

    public SalaryChangeDto reject(Long id, String notes, Long callerId) {
        SalaryChangeRequest r = requirePending(loadInScope(id));
        assertNotOwn(r, callerId);
        if (!StringUtils.hasText(notes)) throw new IllegalArgumentException("Motif du refus obligatoire");
        r.setStatus(SalaryChangeRequest.REJECTED);
        r.setDecidedBy(callerId);
        r.setDecidedAt(OffsetDateTime.now());
        r.setDecisionNotes(notes.trim());
        return toDto(repo.save(r), null);
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    private Predicate<SalaryChangeRequest> inScope() {
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        if (scope == null || scope.all()) return r -> true;
        return r -> r.getPaysId() != null && scope.allows(r.getPaysId());
    }

    /** Out of scope reads as not found. */
    private SalaryChangeRequest loadInScope(Long id) {
        SalaryChangeRequest r = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Demande de révision salariale introuvable : " + id));
        if (!inScope().test(r)) throw new NoSuchElementException("Demande de révision salariale introuvable : " + id);
        return r;
    }

    private static SalaryChangeRequest requirePending(SalaryChangeRequest r) {
        if (!SalaryChangeRequest.PENDING.equals(r.getStatus())) {
            throw new IllegalStateException("Cette demande a déjà été traitée (statut = " + r.getStatus() + ")");
        }
        return r;
    }

    private static void assertNotOwn(SalaryChangeRequest r, Long callerId) {
        if (Objects.equals(r.getProfileUserId(), callerId)) {
            throw new AccessDeniedException("Vous ne pouvez pas décider de votre propre rémunération");
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private List<SalaryChangeDto> toDtos(List<SalaryChangeRequest> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, String> names = names(rows);
        return rows.stream().map(r -> map(r, names, null)).toList();
    }

    private SalaryChangeDto toDto(SalaryChangeRequest r, String currentContract) {
        return map(r, names(List.of(r)), currentContract);
    }

    private SalaryChangeDto map(SalaryChangeRequest r, Map<Long, String> names, String currentContract) {
        RemunerationSimulationDto sim = null;
        if (StringUtils.hasText(r.getSimulationSnapshot())) {
            try {
                sim = objectMapper.readValue(r.getSimulationSnapshot(), RemunerationSimulationDto.class);
            } catch (Exception ignored) {
                // snapshot unreadable: the frozen figures below are enough to decide
            }
        }
        return new SalaryChangeDto(r.getId(), r.getProfileUserId(), names.get(r.getProfileUserId()), r.getPaysId(),
                r.getStatus(), r.getContractType(), currentContract, fromJson(r.getSelectedBenefitCodes()),
                r.getGrossSalary(), r.getInputCurrency(), r.getWorkingCurrency(),
                r.getCurrentGrossSalary(), r.getCurrentNetSalary(), r.getNewGrossSalary(), r.getNewNetSalary(),
                r.getMonthlyCostBefore(), r.getMonthlyCostAfter(), r.getEffectiveDate(), r.getReason(), sim,
                r.getSubmittedBy(), names.get(r.getSubmittedBy()), r.getSubmittedAt(),
                r.getDecidedBy(), r.getDecidedBy() == null ? null : names.get(r.getDecidedBy()), r.getDecidedAt(),
                r.getDecisionNotes());
    }

    private Map<Long, String> names(List<SalaryChangeRequest> rows) {
        Set<Long> ids = new HashSet<>();
        for (SalaryChangeRequest r : rows) {
            ids.add(r.getProfileUserId());
            if (r.getSubmittedBy() != null) ids.add(r.getSubmittedBy());
            if (r.getDecidedBy() != null) ids.add(r.getDecidedBy());
        }
        Map<Long, String> out = new HashMap<>();
        for (UsersRef u : usersRefRepo.findAllById(ids)) out.put(u.getId(), u.getFullName());
        return out;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Données de la demande illisibles", e);
        }
    }

    private List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
