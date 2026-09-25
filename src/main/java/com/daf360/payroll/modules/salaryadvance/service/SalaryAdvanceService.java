package com.daf360.payroll.modules.salaryadvance.service;

import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient.EmployeeFacts;
import com.daf360.payroll.modules.salaryadvance.dto.SalaryAdvanceDtos.*;
import com.daf360.payroll.modules.salaryadvance.entity.*;
import com.daf360.payroll.modules.salaryadvance.repository.*;
import com.daf360.payroll.security.PaysScopeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Salary advances, end to end: the employee asks (self-service), finance approves or declines
 * (its cost approval queue — finance is only the decision hub), payroll records the payout and
 * reconciles the monthly deductions against the payslips.
 *
 * Rules enforced here and NOT by {@code @PreAuthorize}, because they need the row or RH's
 * facts loaded:
 *
 *  - **eligibility** — an active rule for the entity, an employee profile, an active status,
 *    the minimum seniority, no departure in progress, one open advance at a time; the period
 *    within the rule's maximum. No ceiling on the amount (user decision): finance judges it.
 *    The same checks feed {@link #eligibility}, so the self-service modal and the server agree.
 *  - **whose advance this is** — every /my endpoint checks the row belongs to the caller.
 *  - **no one decides on their own advance** — finance and payroll holders are employees too.
 *  - **entity scope** — finance and payroll only act on advances of entities in their scope.
 *
 * Errors follow this service's GlobalExceptionHandler: IllegalArgumentException → 400,
 * IllegalStateException → 409, NoSuchElementException → 404, AccessDeniedException → 403.
 */
@Slf4j
@Service
@Transactional
public class SalaryAdvanceService {

    private static final Set<String> PAYMENT_METHODS = Set.of("ESPECES", "VIREMENT", "CARTE", "AUTRE");

    private final SalaryAdvanceRepository            advanceRepo;
    private final SalaryAdvanceInstallmentRepository installmentRepo;
    private final SalaryAdvancePolicyRepository      policyRepo;
    private final SalaryAdvanceHistoryRepository     historyRepo;
    private final UsersRefRepository                 usersRefRepo;
    private final UserContextService                 userContext;
    private final HrAdvanceClient                    hr;

    public SalaryAdvanceService(SalaryAdvanceRepository advanceRepo,
                                SalaryAdvanceInstallmentRepository installmentRepo,
                                SalaryAdvancePolicyRepository policyRepo,
                                SalaryAdvanceHistoryRepository historyRepo,
                                UsersRefRepository usersRefRepo,
                                UserContextService userContext,
                                HrAdvanceClient hr) {
        this.advanceRepo = advanceRepo;
        this.installmentRepo = installmentRepo;
        this.policyRepo = policyRepo;
        this.historyRepo = historyRepo;
        this.usersRefRepo = usersRefRepo;
        this.userContext = userContext;
        this.hr = hr;
    }

    // ── Employee ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Eligibility eligibility(Long callerId) {
        return evaluate(callerId).eligibility();
    }

    public AdvanceDto submit(SubmitRequest req, Long callerId) {
        Evaluation ev = evaluate(callerId);
        if (!ev.eligibility().eligible()) {
            throw new IllegalStateException("Demande impossible : " + String.join(", ", ev.eligibility().blockers()));
        }
        SalaryAdvancePolicy policy = ev.policy();
        BigDecimal amount = req.amount().setScale(3, RoundingMode.HALF_UP);
        int installments = req.installments();
        YearMonth first = parseMonth(req.firstMonth());
        YearMonth now = YearMonth.parse(ev.facts().currentMonth());

        if (installments > policy.getMaxInstallments()) {
            throw new IllegalArgumentException("Durée de remboursement hors limite (1 à " + policy.getMaxInstallments() + " mois)");
        }
        if (amount.compareTo(new BigDecimal("0.001").multiply(BigDecimal.valueOf(installments))) < 0) {
            throw new IllegalArgumentException("Montant trop faible pour être réparti sur " + installments + " mois");
        }
        if (first.isBefore(now) || first.isAfter(now.plusMonths(3))) {
            throw new IllegalArgumentException("La première retenue doit être entre " + now + " et " + now.plusMonths(3));
        }

        SalaryAdvance a = new SalaryAdvance();
        a.setProfileUserId(callerId);
        a.setPaysId(ev.facts().paysId());
        a.setCurrency(policy.getCurrency());
        a.setAmount(amount);
        a.setInstallments(installments);
        a.setFirstDeductionMonth(first.atDay(1));
        a.setReason(trimToNull(req.reason()));
        a.setStatus(SalaryAdvanceStatus.PENDING_FINANCE);
        SalaryAdvance saved = advanceRepo.save(a);

        trace(saved, null, SalaryAdvanceStatus.PENDING_FINANCE, callerId, "Demande d'avance");
        notifyAfterCommit("SALARY_ADVANCE_PENDING_FINANCE", saved, null);
        return toDto(saved, false);
    }

    @Transactional(readOnly = true)
    public List<AdvanceDto> listMine(Long callerId) {
        return toDtos(advanceRepo.findByProfileUserIdOrderByCreatedAtDesc(callerId));
    }

    @Transactional(readOnly = true)
    public AdvanceDto getMine(Long id, Long callerId) {
        SalaryAdvance a = load(id);
        assertOwn(a, callerId);
        return toDto(a, true);
    }

    /** Withdrawn while finance has not decided — after that, the payout may be in preparation. */
    public AdvanceDto cancelMine(Long id, String reason, Long callerId) {
        SalaryAdvance a = load(id);
        assertOwn(a, callerId);
        requireStatus(a, "Cette avance ne peut plus être retirée", SalaryAdvanceStatus.PENDING_FINANCE);
        a.setCancelledAt(OffsetDateTime.now());
        a.setCancellationReason(trimToNull(reason));
        return move(a, SalaryAdvanceStatus.CANCELLED, callerId, trimToNull(reason), null);
    }

    // ── Finance — the decision hub ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdvanceDto> listPendingFinance() {
        return toDtos(queue(SalaryAdvanceStatus.PENDING_FINANCE));
    }

    public AdvanceDto approve(Long id, String notes, Long callerId) {
        SalaryAdvance a = requireFinanceStep(id, callerId);
        stampFinance(a, callerId, notes);
        return move(a, SalaryAdvanceStatus.APPROVED, callerId, trimToNull(notes), "SALARY_ADVANCE_APPROVED");
    }

    public AdvanceDto reject(Long id, String notes, Long callerId) {
        SalaryAdvance a = requireFinanceStep(id, callerId);
        requireReason(notes);
        stampFinance(a, callerId, notes);
        return move(a, SalaryAdvanceStatus.REJECTED, callerId, notes.trim(), "SALARY_ADVANCE_REJECTED");
    }

    // ── Payroll — payout, deductions, follow-up, rules ──────────────────────

    /** Approved by finance, money not out yet — payroll's payout queue. */
    @Transactional(readOnly = true)
    public List<AdvanceDto> listToDisburse() {
        return toDtos(queue(SalaryAdvanceStatus.APPROVED));
    }

    /** Every advance in scope, optionally narrowed to some statuses. */
    @Transactional(readOnly = true)
    public List<AdvanceDto> listAll(Collection<SalaryAdvanceStatus> statuses) {
        Collection<SalaryAdvanceStatus> wanted = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(SalaryAdvanceStatus.class) : statuses;
        return toDtos(advanceRepo.findByStatusInOrderByCreatedAtDesc(wanted).stream().filter(inScope()).toList());
    }

    /** One employee's advances, in scope — the employee payroll configuration page. */
    @Transactional(readOnly = true)
    public List<AdvanceDto> listForEmployee(Long profileUserId) {
        return toDtos(advanceRepo.findByProfileUserIdOrderByCreatedAtDesc(profileUserId).stream()
                .filter(inScope()).toList());
    }

    @Transactional(readOnly = true)
    public AdvanceDto getById(Long id) {
        return toDto(loadInScope(id), true);
    }

    /**
     * Payroll records that the money left. This is what creates the schedule. The first
     * deduction is pushed to the current month if it has already passed — a late payout is
     * never recovered retroactively.
     */
    public AdvanceDto disburse(Long id, DisburseRequest req, Long callerId) {
        SalaryAdvance a = loadInScope(id);
        assertNotOwn(a, callerId);
        requireStatus(a, "Cette avance n'est pas en attente de versement", SalaryAdvanceStatus.APPROVED);

        String method = req.method().trim().toUpperCase();
        if (!PAYMENT_METHODS.contains(method)) {
            throw new IllegalArgumentException("Mode de paiement inconnu : " + req.method());
        }
        YearMonth now = YearMonth.from(LocalDate.now());
        YearMonth first = YearMonth.from(a.getFirstDeductionMonth());
        String shifted = null;
        if (first.isBefore(now)) {
            shifted = "Première retenue reportée de " + first + " à " + now + " (versement tardif)";
            first = now;
            a.setFirstDeductionMonth(first.atDay(1));
        }

        a.setDisbursementMethod(method);
        a.setDisbursementReference(trimToNull(req.reference()));
        a.setDisbursedOn(req.disbursedOn());
        a.setDisbursedBy(callerId);
        a.setDisbursedAt(OffsetDateTime.now());
        a.setOutstandingAmount(a.getAmount());
        installmentRepo.saveAll(buildSchedule(a.getId(), a.getAmount(), a.getInstallments(), first));

        String notes = join("Versement " + a.getAmount().toPlainString() + " " + a.getCurrency()
                + " (" + method + ") le " + req.disbursedOn(), join(shifted, trimToNull(req.notes())));
        return move(a, SalaryAdvanceStatus.REPAYING, callerId, notes, "SALARY_ADVANCE_DISBURSED");
    }

    /** The month's lines, in scope, with names and matricules from RH for the export. */
    @Transactional(readOnly = true)
    public List<DeductionRowDto> deductions(YearMonth month) {
        List<SalaryAdvanceInstallment> lines = installmentRepo.findDueIn(month.getYear(), month.getMonthValue());
        if (lines.isEmpty()) return List.of();

        Map<Long, SalaryAdvance> advances = advanceRepo.findAllById(
                        lines.stream().map(SalaryAdvanceInstallment::getSalaryAdvanceId).distinct().toList())
                .stream().filter(inScope()).collect(Collectors.toMap(SalaryAdvance::getId, x -> x));
        Set<Long> userIds = advances.values().stream().map(SalaryAdvance::getProfileUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, EmployeeFacts> facts = hr.employees(userIds);
        Map<Long, String> names = names(userIds);

        List<DeductionRowDto> rows = new ArrayList<>();
        for (SalaryAdvanceInstallment i : lines) {
            SalaryAdvance a = advances.get(i.getSalaryAdvanceId());
            if (a == null) continue;
            EmployeeFacts f = facts.get(a.getProfileUserId());
            rows.add(new DeductionRowDto(
                    i.getId(), a.getId(), a.getProfileUserId(),
                    names.getOrDefault(a.getProfileUserId(), f == null ? null : f.fullName()),
                    f == null ? null : f.payrollMatricule(),
                    a.getPaysId(), i.period().toString(), i.getSeq(), a.getInstallments(),
                    i.getAmount(), a.getCurrency(), i.getStatus(), a.getOutstandingAmount()));
        }
        return rows;
    }

    /** The payslip carried these lines. Closes every advance whose last line this was. */
    public int markDeducted(InstallmentActionRequest req, Long callerId) {
        return settle(req, InstallmentStatus.DEDUCTED, callerId, false);
    }

    /** Settled another way (final settlement, cash) or written off. Notes required. */
    public int waive(InstallmentActionRequest req, Long callerId) {
        return settle(req, InstallmentStatus.WAIVED, callerId, true);
    }

    /** Not taken this month: re-planned one month after the current last line. Notes required. */
    public int skip(InstallmentActionRequest req, Long callerId) {
        requireReason(req.notes());
        List<SalaryAdvanceInstallment> lines = loadPlanned(req.installmentIds());
        Map<Long, SalaryAdvance> advances = advancesFor(lines, callerId);

        for (SalaryAdvanceInstallment line : lines) {
            stamp(line, InstallmentStatus.SKIPPED, callerId, req.notes().trim());
            installmentRepo.save(line);

            List<SalaryAdvanceInstallment> schedule = installmentRepo.findBySalaryAdvanceIdOrderBySeqAsc(line.getSalaryAdvanceId());
            SalaryAdvanceInstallment last = schedule.get(schedule.size() - 1);
            YearMonth next = last.period().plusMonths(1);
            SalaryAdvanceInstallment moved = new SalaryAdvanceInstallment();
            moved.setSalaryAdvanceId(line.getSalaryAdvanceId());
            moved.setSeq(last.getSeq() + 1);
            moved.setPeriod(next);
            moved.setAmount(line.getAmount());
            moved.setStatus(InstallmentStatus.PLANNED);
            installmentRepo.save(moved);

            SalaryAdvance a = advances.get(line.getSalaryAdvanceId());
            trace(a, a.getStatus(), a.getStatus(), callerId,
                  "Retenue de " + line.period() + " non prélevée, reportée à " + next + " : " + req.notes().trim());
        }
        return lines.size();
    }

    @Transactional(readOnly = true)
    public List<PolicyDto> listPolicies() {
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        return policyRepo.findAllByOrderByPaysIdAsc().stream()
                .filter(p -> scope == null || scope.all() || scope.allows(p.getPaysId()))
                .map(this::toPolicyDto).toList();
    }

    public PolicyDto savePolicy(Long paysId, PolicyDto dto, Long callerId) {
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        if (scope != null && !scope.all() && !scope.allows(paysId)) {
            throw new AccessDeniedException("Ce pays est hors de votre périmètre");
        }
        SalaryAdvancePolicy p = policyRepo.findByPaysId(paysId).orElseGet(() -> {
            SalaryAdvancePolicy fresh = new SalaryAdvancePolicy();
            fresh.setPaysId(paysId);
            return fresh;
        });
        p.setCurrency(dto.currency().trim().toUpperCase());
        p.setMaxInstallments(dto.maxInstallments());
        p.setMinSeniorityMonths(dto.minSeniorityMonths());
        p.setIsActive(dto.isActive());
        p.setUpdatedBy(callerId);
        return toPolicyDto(policyRepo.save(p));
    }

    // ── Eligibility ─────────────────────────────────────────────────────────

    private record Evaluation(Eligibility eligibility, EmployeeFacts facts, SalaryAdvancePolicy policy) {}

    /** Collects ALL blockers rather than stopping at the first, so the modal lists them at once. */
    private Evaluation evaluate(Long callerId) {
        List<String> blockers = new ArrayList<>();
        Optional<EmployeeFacts> found = hr.employee(callerId);
        if (found.isEmpty()) {
            return new Evaluation(new Eligibility(false, List.of("NO_PROFILE"), null, null, null, null), null, null);
        }
        EmployeeFacts f = found.get();
        SalaryAdvancePolicy policy = f.paysId() == null ? null
                : policyRepo.findByPaysId(f.paysId()).filter(p -> Boolean.TRUE.equals(p.getIsActive())).orElse(null);
        if (policy == null) {
            return new Evaluation(new Eligibility(false, List.of("NO_POLICY"), null, null, null, f.currentMonth()), f, null);
        }

        if (!f.active()) blockers.add("NOT_ACTIVE");
        int minMonths = policy.getMinSeniorityMonths() == null ? 0 : policy.getMinSeniorityMonths();
        if (minMonths > 0) {
            if (f.hireDate() == null || ChronoUnit.MONTHS.between(f.hireDate(), LocalDate.now()) < minMonths) {
                blockers.add("SENIORITY");
            }
        }
        if (advanceRepo.existsByProfileUserIdAndStatusIn(callerId, SalaryAdvanceStatus.OPEN)) blockers.add("OPEN_ADVANCE");
        if (f.offboardingInProgress()) blockers.add("OFFBOARDING");

        return new Evaluation(new Eligibility(blockers.isEmpty(), blockers, policy.getCurrency(),
                policy.getMaxInstallments(), policy.getMinSeniorityMonths(), f.currentMonth()), f, policy);
    }

    // ── Schedule ────────────────────────────────────────────────────────────

    /** Equal lines rounded down to the millime; the last takes the rest, so the sum is exact. */
    static List<SalaryAdvanceInstallment> buildSchedule(Long advanceId, BigDecimal amount, int installments, YearMonth first) {
        BigDecimal monthly = monthlyAmount(amount, installments);
        List<SalaryAdvanceInstallment> lines = new ArrayList<>(installments);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int seq = 1; seq <= installments; seq++) {
            BigDecimal value = seq < installments ? monthly : amount.subtract(allocated);
            allocated = allocated.add(value);
            SalaryAdvanceInstallment line = new SalaryAdvanceInstallment();
            line.setSalaryAdvanceId(advanceId);
            line.setSeq(seq);
            line.setPeriod(first.plusMonths(seq - 1L));
            line.setAmount(value);
            line.setStatus(InstallmentStatus.PLANNED);
            lines.add(line);
        }
        return lines;
    }

    static BigDecimal monthlyAmount(BigDecimal amount, int installments) {
        return amount.divide(BigDecimal.valueOf(installments), 3, RoundingMode.DOWN);
    }

    private int settle(InstallmentActionRequest req, InstallmentStatus target, Long callerId, boolean reasonRequired) {
        if (reasonRequired) requireReason(req.notes());
        List<SalaryAdvanceInstallment> lines = loadPlanned(req.installmentIds());
        Map<Long, SalaryAdvance> advances = advancesFor(lines, callerId);

        Map<Long, List<SalaryAdvanceInstallment>> byAdvance = lines.stream().collect(
                Collectors.groupingBy(SalaryAdvanceInstallment::getSalaryAdvanceId, LinkedHashMap::new, Collectors.toList()));
        for (var entry : byAdvance.entrySet()) {
            SalaryAdvance a = advances.get(entry.getKey());
            BigDecimal total = BigDecimal.ZERO;
            List<String> months = new ArrayList<>();
            for (SalaryAdvanceInstallment line : entry.getValue()) {
                stamp(line, target, callerId, trimToNull(req.notes()));
                total = total.add(line.getAmount());
                months.add(line.period().toString());
            }
            installmentRepo.saveAll(entry.getValue());

            BigDecimal outstanding = a.getOutstandingAmount() == null ? BigDecimal.ZERO : a.getOutstandingAmount();
            a.setOutstandingAmount(outstanding.subtract(total).max(BigDecimal.ZERO));
            String notes = join((target == InstallmentStatus.DEDUCTED ? "Retenue sur paie " : "Mensualité soldée autrement ")
                    + String.join(", ", months) + " — " + total.toPlainString() + " " + a.getCurrency(), trimToNull(req.notes()));

            if (!installmentRepo.existsBySalaryAdvanceIdAndStatus(a.getId(), InstallmentStatus.PLANNED)) {
                a.setOutstandingAmount(BigDecimal.ZERO.setScale(3));
                a.setRepaidAt(OffsetDateTime.now());
                move(a, SalaryAdvanceStatus.REPAID, callerId, notes, null);
            } else {
                SalaryAdvance saved = advanceRepo.save(a);
                trace(saved, saved.getStatus(), saved.getStatus(), callerId, notes);
            }
        }
        return lines.size();
    }

    private static void stamp(SalaryAdvanceInstallment line, InstallmentStatus status, Long callerId, String notes) {
        line.setStatus(status);
        line.setProcessedBy(callerId);
        line.setProcessedAt(OffsetDateTime.now());
        line.setNotes(notes);
    }

    private List<SalaryAdvanceInstallment> loadPlanned(List<Long> ids) {
        Set<Long> wanted = new LinkedHashSet<>(ids);
        List<SalaryAdvanceInstallment> lines = installmentRepo.findAllById(wanted);
        if (lines.size() != wanted.size()) throw new NoSuchElementException("Mensualité introuvable");
        for (SalaryAdvanceInstallment line : lines) {
            if (line.getStatus() != InstallmentStatus.PLANNED) {
                throw new IllegalStateException("La mensualité " + line.period() + " a déjà été traitée (" + line.getStatus() + ")");
            }
        }
        return lines;
    }

    /** The advances owning these lines — all REPAYING, in scope, and not the caller's own. */
    private Map<Long, SalaryAdvance> advancesFor(List<SalaryAdvanceInstallment> lines, Long callerId) {
        Map<Long, SalaryAdvance> advances = advanceRepo.findAllById(
                        lines.stream().map(SalaryAdvanceInstallment::getSalaryAdvanceId).distinct().toList())
                .stream().collect(Collectors.toMap(SalaryAdvance::getId, x -> x));
        for (SalaryAdvance a : advances.values()) {
            if (!inScope().test(a)) throw new AccessDeniedException("Avance hors de votre périmètre");
            assertNotOwn(a, callerId);
            if (a.getStatus() != SalaryAdvanceStatus.REPAYING) {
                throw new IllegalStateException("L'avance #" + a.getId() + " n'est pas en cours de remboursement");
            }
        }
        return advances;
    }

    // ── Transitions ─────────────────────────────────────────────────────────

    private AdvanceDto move(SalaryAdvance a, SalaryAdvanceStatus to, Long actorId, String notes, String event) {
        SalaryAdvanceStatus from = a.getStatus();
        a.setStatus(to);
        SalaryAdvance saved = advanceRepo.save(a);
        trace(saved, from, to, actorId, notes);
        if (event != null) notifyAfterCommit(event, saved, notes);
        return toDto(saved, true);
    }

    private SalaryAdvance requireFinanceStep(Long id, Long callerId) {
        SalaryAdvance a = loadInScope(id);
        assertNotOwn(a, callerId);
        requireStatus(a, "Cette avance n'attend pas de décision finance", SalaryAdvanceStatus.PENDING_FINANCE);
        return a;
    }

    private static void stampFinance(SalaryAdvance a, Long callerId, String notes) {
        a.setFinanceDecidedBy(callerId);
        a.setFinanceDecidedAt(OffsetDateTime.now());
        a.setFinanceNotes(trimToNull(notes));
    }

    private void trace(SalaryAdvance a, SalaryAdvanceStatus from, SalaryAdvanceStatus to, Long actorId, String notes) {
        SalaryAdvanceHistory h = new SalaryAdvanceHistory();
        h.setSalaryAdvanceId(a.getId());
        h.setFromStatus(from == null ? null : from.name());
        h.setToStatus(to.name());
        h.setActorUserId(actorId);
        h.setNotes(notes == null ? null : (notes.length() > 1000 ? notes.substring(0, 1000) : notes));
        historyRepo.save(h);
    }

    /**
     * Sent through RH once the transaction has committed: a notification about a decision that
     * then rolled back would be a lie, and the HTTP call must not hold the transaction open.
     */
    private void notifyAfterCommit(String event, SalaryAdvance a, String notes) {
        Map<String, String> vars = new HashMap<>();
        vars.put("employeeName", Objects.toString(names(List.of(a.getProfileUserId())).get(a.getProfileUserId()), ""));
        vars.put("amount", a.getAmount().toPlainString());
        vars.put("currency", a.getCurrency());
        vars.put("installments", String.valueOf(a.getInstallments()));
        vars.put("firstMonth", YearMonth.from(a.getFirstDeductionMonth()).toString());
        vars.put("notes", notes == null ? "" : notes);
        Long paysId = a.getPaysId(), userId = a.getProfileUserId(), advanceId = a.getId();
        Runnable send = () -> hr.notify(event, paysId, userId, advanceId, vars);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    // ── Scope & guards ──────────────────────────────────────────────────────

    private List<SalaryAdvance> queue(SalaryAdvanceStatus status) {
        return advanceRepo.findByStatusOrderByCreatedAtAsc(status).stream().filter(inScope()).toList();
    }

    /** The caller's entity scope; null = permissive, as the rest of this service treats it. */
    private Predicate<SalaryAdvance> inScope() {
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        if (scope == null || scope.all()) return a -> true;
        return a -> a.getPaysId() != null && scope.allows(a.getPaysId());
    }

    private SalaryAdvance load(Long id) {
        return advanceRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Avance introuvable : " + id));
    }

    /** Out of scope reads as not found: no need to confirm the id exists in another entity. */
    private SalaryAdvance loadInScope(Long id) {
        SalaryAdvance a = load(id);
        if (!inScope().test(a)) throw new NoSuchElementException("Avance introuvable : " + id);
        return a;
    }

    private static void requireStatus(SalaryAdvance a, String message, SalaryAdvanceStatus... allowed) {
        if (!Arrays.asList(allowed).contains(a.getStatus())) {
            throw new IllegalStateException(message + " (statut = " + a.getStatus() + ")");
        }
    }

    private static void assertOwn(SalaryAdvance a, Long callerId) {
        if (!Objects.equals(a.getProfileUserId(), callerId)) throw new AccessDeniedException("Cette avance n'est pas la vôtre");
    }

    private static void assertNotOwn(SalaryAdvance a, Long callerId) {
        if (Objects.equals(a.getProfileUserId(), callerId)) {
            throw new AccessDeniedException("Vous ne pouvez pas traiter votre propre avance");
        }
    }

    private static void requireReason(String notes) {
        if (!StringUtils.hasText(notes)) throw new IllegalArgumentException("Motif obligatoire");
    }

    static YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Mois invalide (attendu : AAAA-MM) : " + value);
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private List<AdvanceDto> toDtos(List<SalaryAdvance> advances) {
        if (advances.isEmpty()) return List.of();
        List<Long> ids = advances.stream().map(SalaryAdvance::getId).toList();
        Map<Long, List<SalaryAdvanceInstallment>> schedules = installmentRepo.findBySalaryAdvanceIdInOrderBySeqAsc(ids)
                .stream().collect(Collectors.groupingBy(SalaryAdvanceInstallment::getSalaryAdvanceId));
        Set<Long> userIds = new LinkedHashSet<>();
        advances.forEach(a -> addUsers(userIds, a));
        Map<Long, String> names = names(userIds);
        return advances.stream().map(a -> map(a, schedules.getOrDefault(a.getId(), List.of()), names, List.of())).toList();
    }

    private AdvanceDto toDto(SalaryAdvance a, boolean withHistory) {
        List<SalaryAdvanceInstallment> schedule = installmentRepo.findBySalaryAdvanceIdOrderBySeqAsc(a.getId());
        List<SalaryAdvanceHistory> history = withHistory ? historyRepo.findBySalaryAdvanceIdOrderByCreatedAtAsc(a.getId()) : List.of();
        Set<Long> userIds = new LinkedHashSet<>();
        addUsers(userIds, a);
        history.forEach(h -> { if (h.getActorUserId() != null) userIds.add(h.getActorUserId()); });
        return map(a, schedule, names(userIds), history);
    }

    private AdvanceDto map(SalaryAdvance a, List<SalaryAdvanceInstallment> schedule, Map<Long, String> names,
                           List<SalaryAdvanceHistory> history) {
        return new AdvanceDto(
                a.getId(), a.getPaysId(), a.getProfileUserId(), names.get(a.getProfileUserId()),
                a.getCurrency(), a.getAmount(), a.getInstallments(),
                YearMonth.from(a.getFirstDeductionMonth()).toString(),
                monthlyAmount(a.getAmount(), a.getInstallments()),
                a.getReason(), a.getStatus(),
                names.get(a.getFinanceDecidedBy()), a.getFinanceDecidedAt(), a.getFinanceNotes(),
                a.getDisbursementMethod(), a.getDisbursementReference(), a.getDisbursedOn(),
                names.get(a.getDisbursedBy()),
                a.getOutstandingAmount(), a.getRepaidAt(), a.getCancelledAt(), a.getCancellationReason(),
                a.getCreatedAt(),
                schedule.stream().map(i -> new InstallmentDto(i.getId(), i.getSeq(), i.period().toString(),
                        i.getAmount(), i.getStatus(), i.getProcessedAt(), i.getNotes())).toList(),
                history.stream().map(h -> new HistoryEntryDto(h.getFromStatus(), h.getToStatus(),
                        names.get(h.getActorUserId()), h.getNotes(), h.getCreatedAt())).toList());
    }

    private PolicyDto toPolicyDto(SalaryAdvancePolicy p) {
        return new PolicyDto(p.getPaysId(), p.getCurrency(), p.getMaxInstallments(), p.getMinSeniorityMonths(),
                p.getIsActive(), p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt());
    }

    /**
     * Names from users_ref, payroll's own copy of RH's users (synced every 15 minutes).
     * Always a HashMap: callers look up nullable ids (who decided, who paid out), and
     * {@code Map.of().get(null)} throws.
     */
    private Map<Long, String> names(Collection<Long> userIds) {
        Map<Long, String> byId = new HashMap<>();
        List<Long> ids = userIds.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return byId;
        usersRefRepo.findAllById(ids).forEach(u -> byId.put(u.getId(), u.getFullName()));
        return byId;
    }

    private static void addUsers(Set<Long> target, SalaryAdvance a) {
        for (Long id : new Long[] { a.getProfileUserId(), a.getFinanceDecidedBy(), a.getDisbursedBy() }) {
            if (id != null) target.add(id);
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " — " + b;
    }
}
