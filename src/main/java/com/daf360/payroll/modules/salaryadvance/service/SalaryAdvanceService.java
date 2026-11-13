package com.daf360.payroll.modules.salaryadvance.service;

import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient.ContractRequest;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient.EmployeeFacts;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient.ScheduleLine;
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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Salary advances, end to end (« Spécification fonctionnelle – avance sur salaire », V39):
 *
 *  1. the employee asks from self-service — amount, desired availability date, months;
 *  2. above the country threshold, a holder of FACT_VALIDATE_SALARY_ADVANCE_ABOVE_THRESHOLD
 *     validates first ({@code PENDING_DIRECTOR}); otherwise it goes straight to the DAF;
 *  3. the DAF (FACT_APPROVE_SALARY_ADVANCE) grants it with the final terms — granted amount,
 *     months, date d'effet, first deduction month. That builds the schedule and has RH
 *     generate the debt contract into the employee's RH folder;
 *  4. the DAF uploads the signed, legalised contract (RH archives it, SharePoint-mirrored);
 *  5. payroll confirms the payout — blocked until the contract is signed where the country
 *     requires it — and reconciles the monthly deductions against the payslips.
 *
 * Rules enforced here and NOT by {@code @PreAuthorize}, because they need the row or RH's
 * facts loaded:
 *
 *  - **eligibility** — an active rule for the entity, an employee profile, an active status,
 *    the minimum seniority, no departure in progress, one open advance at a time; the period
 *    within the rule's maximum. No ceiling on the amount (user decision): the DAF judges it.
 *    The same checks feed {@link #eligibility}, so the self-service modal and the server agree.
 *  - **whose advance this is** — every /my endpoint checks the row belongs to the caller.
 *  - **no one acts on their own advance** — directors, DAF and payroll are employees too.
 *  - **entity scope** — every desk only acts on advances of entities in its scope.
 *
 * Errors follow this service's GlobalExceptionHandler: IllegalArgumentException → 400,
 * IllegalStateException → 409, NoSuchElementException → 404, AccessDeniedException → 403.
 */
@Slf4j
@Service
@Transactional
public class SalaryAdvanceService {

    private static final Set<String> PAYMENT_METHODS = Set.of("ESPECES", "VIREMENT", "CARTE", "AUTRE");
    private static final Set<String> CONTRACT_TYPES  = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final long        CONTRACT_MAX_BYTES = 10L * 1024 * 1024;
    private static final DateTimeFormatter FR_DATE  = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FR_MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    /** How far ahead an employee may ask the money for. */
    private static final int MAX_AVAILABILITY_MONTHS_AHEAD = 6;

    private final SalaryAdvanceRepository            advanceRepo;
    private final SalaryAdvanceInstallmentRepository installmentRepo;
    private final SalaryAdvancePolicyRepository      policyRepo;
    private final SalaryAdvanceHistoryRepository     historyRepo;
    private final UsersRefRepository                 usersRefRepo;
    private final UserContextService                 userContext;
    private final HrAdvanceClient                     hr;

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

    /**
     * Records the request « En attente de traitement ». Above the country threshold it waits
     * for the director first; otherwise the DAF is notified (in-app + e-mail recap) at once.
     */
    public AdvanceDto submit(SubmitRequest req, Long callerId) {
        Evaluation ev = evaluate(callerId);
        if (!ev.eligibility().eligible()) {
            throw new IllegalStateException("Demande impossible : " + String.join(", ", ev.eligibility().blockers()));
        }
        SalaryAdvancePolicy policy = ev.policy();
        BigDecimal amount = req.amount().setScale(3, RoundingMode.HALF_UP);
        int installments = req.installments();
        LocalDate today = LocalDate.now();

        if (installments > policy.getMaxInstallments()) {
            throw new IllegalArgumentException("Durée de remboursement hors limite (1 à " + policy.getMaxInstallments() + " mois)");
        }
        if (amount.compareTo(new BigDecimal("0.001").multiply(BigDecimal.valueOf(installments))) < 0) {
            throw new IllegalArgumentException("Montant trop faible pour être réparti sur " + installments + " mois");
        }
        if (req.availabilityDate().isBefore(today)
                || req.availabilityDate().isAfter(today.plusMonths(MAX_AVAILABILITY_MONTHS_AHEAD))) {
            throw new IllegalArgumentException("La date souhaitée doit être entre aujourd'hui et "
                    + today.plusMonths(MAX_AVAILABILITY_MONTHS_AHEAD).format(FR_DATE));
        }

        boolean aboveThreshold = policy.getDirectorThreshold() != null
                && amount.compareTo(policy.getDirectorThreshold()) > 0;
        SalaryAdvanceStatus first = aboveThreshold ? SalaryAdvanceStatus.PENDING_DIRECTOR : SalaryAdvanceStatus.PENDING_FINANCE;

        SalaryAdvance a = new SalaryAdvance();
        a.setProfileUserId(callerId);
        a.setPaysId(ev.facts().paysId());
        a.setCurrency(policy.getCurrency());
        a.setAmount(amount);
        a.setInstallments(installments);
        a.setRequestedAmount(amount);
        a.setRequestedInstallments(installments);
        a.setRequestedAvailabilityDate(req.availabilityDate());
        a.setReason(trimToNull(req.reason()));
        a.setStatus(first);
        SalaryAdvance saved = advanceRepo.save(a);

        trace(saved, null, first, callerId, aboveThreshold
                ? "Demande d'avance — au-dessus du seuil de " + policy.getDirectorThreshold().toPlainString() + " " + policy.getCurrency()
                  + " : validation préalable requise"
                : "Demande d'avance");
        notifyAfterCommit(aboveThreshold ? "SALARY_ADVANCE_PENDING_DIRECTOR" : "SALARY_ADVANCE_PENDING_FINANCE", saved, null);
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

    /** Withdrawn while nobody has decided — after that, the terms and the contract exist. */
    public AdvanceDto cancelMine(Long id, String reason, Long callerId) {
        SalaryAdvance a = load(id);
        assertOwn(a, callerId);
        requireStatus(a, "Cette avance ne peut plus être retirée",
                SalaryAdvanceStatus.PENDING_DIRECTOR, SalaryAdvanceStatus.PENDING_FINANCE);
        a.setCancelledAt(OffsetDateTime.now());
        a.setCancellationReason(trimToNull(reason));
        return move(a, SalaryAdvanceStatus.CANCELLED, callerId, trimToNull(reason), null);
    }

    // ── Director — above the country threshold ──────────────────────────────

    @Transactional(readOnly = true)
    public List<AdvanceDto> listPendingDirector() {
        return toDtos(queue(SalaryAdvanceStatus.PENDING_DIRECTOR));
    }

    /** Validated: on to the DAF, who sets the terms. */
    public AdvanceDto directorApprove(Long id, String notes, Long callerId) {
        SalaryAdvance a = requireDirectorStep(id, callerId);
        stampDirector(a, callerId, notes);
        return move(a, SalaryAdvanceStatus.PENDING_FINANCE, callerId,
                join("Validée au-dessus du seuil", trimToNull(notes)), "SALARY_ADVANCE_PENDING_FINANCE");
    }

    public AdvanceDto directorReject(Long id, String notes, Long callerId) {
        SalaryAdvance a = requireDirectorStep(id, callerId);
        requireReason(notes);
        stampDirector(a, callerId, notes);
        return move(a, SalaryAdvanceStatus.REJECTED, callerId, notes.trim(), "SALARY_ADVANCE_REJECTED");
    }

    // ── DAF — final terms, decision, contract ───────────────────────────────

    @Transactional(readOnly = true)
    public List<AdvanceDto> listPendingFinance() {
        return toDtos(queue(SalaryAdvanceStatus.PENDING_FINANCE));
    }

    /**
     * Grants the advance with its final terms. Builds the schedule from them and marks the
     * contract « à signer ». The contract itself is generated by {@link #generateContract},
     * called by the controller once this transaction has committed — RH reads nothing from
     * this database, but the HTTP call must not hold the transaction open.
     */
    public AdvanceDto approve(Long id, ApproveRequest req, Long callerId) {
        SalaryAdvance a = requireFinanceStep(id, callerId);
        SalaryAdvancePolicy policy = policyRepo.findByPaysId(a.getPaysId()).orElse(null);

        BigDecimal granted = req.grantedAmount().setScale(3, RoundingMode.HALF_UP);
        int installments = req.grantedInstallments();
        int max = policy != null && policy.getMaxInstallments() != null ? policy.getMaxInstallments() : 24;
        if (installments > max) {
            throw new IllegalArgumentException("Durée de remboursement hors limite (1 à " + max + " mois)");
        }
        if (granted.compareTo(new BigDecimal("0.001").multiply(BigDecimal.valueOf(installments))) < 0) {
            throw new IllegalArgumentException("Montant trop faible pour être réparti sur " + installments + " mois");
        }
        // A grant above the threshold needs the director's validation, whatever was asked.
        if (policy != null && policy.getDirectorThreshold() != null
                && granted.compareTo(policy.getDirectorThreshold()) > 0 && a.getDirectorDecidedAt() == null) {
            throw new IllegalStateException("Montant accordé au-dessus du seuil de "
                    + policy.getDirectorThreshold().toPlainString() + " " + a.getCurrency()
                    + " : la demande doit d'abord être validée au-dessus du seuil");
        }
        YearMonth first = parseMonth(req.firstDeductionMonth());
        YearMonth now = YearMonth.now();
        if (first.isBefore(now)) {
            throw new IllegalArgumentException("Le début du prélèvement ne peut pas être antérieur à " + monthLabel(now));
        }
        if (first.isBefore(YearMonth.from(req.effectiveDate()))) {
            throw new IllegalArgumentException("Le prélèvement ne peut pas commencer avant le mois de la date d'effet");
        }

        a.setAmount(granted);
        a.setInstallments(installments);
        a.setEffectiveDate(req.effectiveDate());
        a.setFirstDeductionMonth(first.atDay(1));
        a.setOutstandingAmount(granted);
        a.setContractStatus(ContractStatus.TO_SIGN);
        stampFinance(a, callerId, req.notes());

        installmentRepo.saveAll(buildSchedule(a.getId(), granted, installments, first));

        String terms = "Accordée : " + granted.toPlainString() + " " + a.getCurrency()
                + " en " + installments + " mensualité(s) de " + monthlyAmount(granted, installments).toPlainString()
                + ", date d'effet " + req.effectiveDate().format(FR_DATE)
                + ", prélèvement à partir de " + monthLabel(first);
        return move(a, SalaryAdvanceStatus.APPROVED, callerId, join(terms, trimToNull(req.notes())), "SALARY_ADVANCE_APPROVED");
    }

    public AdvanceDto reject(Long id, String notes, Long callerId) {
        SalaryAdvance a = requireFinanceStep(id, callerId);
        requireReason(notes);
        stampFinance(a, callerId, notes);
        return move(a, SalaryAdvanceStatus.REJECTED, callerId, notes.trim(), "SALARY_ADVANCE_REJECTED");
    }

    /**
     * Has RH generate the debt contract from the granted terms and file it in the employee's
     * RH folder. Re-runnable until the signed copy is in: each run files a new document and
     * the advance points at the latest. Throws (409) when RH cannot do it.
     */
    public AdvanceDto generateContract(Long id, Long callerId) {
        SalaryAdvance a = loadInScope(id);
        assertNotOwn(a, callerId);
        requireStatus(a, "Le contrat n'est généré que pour une avance accordée",
                SalaryAdvanceStatus.APPROVED, SalaryAdvanceStatus.REPAYING);
        if (a.getContractStatus() == ContractStatus.SIGNED) {
            throw new IllegalStateException("Le contrat signé est déjà archivé");
        }
        List<SalaryAdvanceInstallment> schedule = installmentRepo.findBySalaryAdvanceIdOrderBySeqAsc(a.getId());
        Long docId = hr.generateContract(new ContractRequest(
                a.getId(), a.getProfileUserId(), a.getPaysId(), a.getCurrency(),
                a.getAmount(), a.getInstallments(), monthlyAmount(a.getAmount(), a.getInstallments()),
                a.getEffectiveDate(),
                a.getFirstDeductionMonth() == null ? null : YearMonth.from(a.getFirstDeductionMonth()).toString(),
                schedule.isEmpty() ? null : schedule.get(schedule.size() - 1).period().toString(),
                schedule.stream().map(i -> new ScheduleLine(i.getSeq(), i.period().toString(), i.getAmount())).toList(),
                callerId));

        boolean regenerated = a.getContractDocumentId() != null;
        a.setContractDocumentId(docId);
        a.setContractGeneratedAt(OffsetDateTime.now());
        if (a.getContractStatus() == null) a.setContractStatus(ContractStatus.TO_SIGN);
        SalaryAdvance saved = advanceRepo.save(a);
        trace(saved, saved.getStatus(), saved.getStatus(), callerId,
                regenerated ? "Contrat de dette régénéré et archivé au dossier RH"
                            : "Contrat de dette généré et archivé au dossier RH — à signer au bureau RH");
        return toDto(saved, true);
    }

    /**
     * The signed, legalised contract — archived by RH in the employee's folder. Unblocks the
     * payout where the country requires it, and tells payroll.
     */
    public AdvanceDto uploadSignedContract(Long id, byte[] content, String fileName, String contentType, Long callerId) {
        SalaryAdvance a = loadInScope(id);
        assertNotOwn(a, callerId);
        requireStatus(a, "Le contrat signé ne se dépose que pour une avance accordée",
                SalaryAdvanceStatus.APPROVED, SalaryAdvanceStatus.REPAYING, SalaryAdvanceStatus.REPAID);
        if (a.getContractStatus() == ContractStatus.SIGNED) {
            throw new IllegalStateException("Le contrat signé est déjà archivé");
        }
        if (content == null || content.length == 0) throw new IllegalArgumentException("Fichier vide");
        if (content.length > CONTRACT_MAX_BYTES) throw new IllegalArgumentException("Fichier trop volumineux (10 Mo maximum)");
        if (contentType == null || !CONTRACT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Format non accepté : PDF, JPG ou PNG");
        }
        String name = StringUtils.hasText(fileName) ? fileName : "contrat-avance-" + a.getId() + "-signe.pdf";

        Long docId = hr.archiveSignedContract(a.getId(), a.getProfileUserId(), callerId, content, name, contentType);
        a.setSignedContractDocumentId(docId);
        a.setSignedContractUploadedBy(callerId);
        a.setSignedContractUploadedAt(OffsetDateTime.now());
        a.setContractStatus(ContractStatus.SIGNED);
        SalaryAdvance saved = advanceRepo.save(a);
        trace(saved, saved.getStatus(), saved.getStatus(), callerId,
                "Contrat signé et légalisé déposé et archivé au dossier RH");
        notifyAfterCommit("SALARY_ADVANCE_CONTRACT_SIGNED", saved, null);
        return toDto(saved, true);
    }

    /** The generated contract ({@code signed = false}) or the signed scan. */
    @Transactional(readOnly = true)
    public ContractFile contract(Long id, boolean signed) {
        SalaryAdvance a = loadInScope(id);
        Long docId = signed ? a.getSignedContractDocumentId() : a.getContractDocumentId();
        if (docId == null) {
            throw new NoSuchElementException(signed ? "Aucun contrat signé déposé" : "Aucun contrat généré");
        }
        return hr.downloadContract(a.getProfileUserId(), docId);
    }

    // ── Read — the « Avances sur salaire » page and payroll ──────────────────

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

    // ── Payroll — payout, deductions, rules ─────────────────────────────────

    /** Granted, money not out yet — payroll's payout queue. */
    @Transactional(readOnly = true)
    public List<AdvanceDto> listToDisburse() {
        return toDtos(queue(SalaryAdvanceStatus.APPROVED));
    }

    /**
     * Payroll confirms the money left. The schedule already exists (built at approval); a
     * payout recorded after the planned first month pushes every line forward — a late payout
     * is never recovered retroactively.
     */
    public AdvanceDto disburse(Long id, DisburseRequest req, Long callerId) {
        SalaryAdvance a = loadInScope(id);
        assertNotOwn(a, callerId);
        requireStatus(a, "Cette avance n'est pas en attente de versement", SalaryAdvanceStatus.APPROVED);

        SalaryAdvancePolicy policy = policyRepo.findByPaysId(a.getPaysId()).orElse(null);
        boolean contractRequired = policy == null || !Boolean.FALSE.equals(policy.getRequireSignedContract());
        if (contractRequired && a.getContractStatus() != ContractStatus.SIGNED) {
            throw new IllegalStateException("Le contrat de dette signé et légalisé doit être déposé avant le versement");
        }
        String method = req.method().trim().toUpperCase();
        if (!PAYMENT_METHODS.contains(method)) {
            throw new IllegalArgumentException("Mode de paiement inconnu : " + req.method());
        }

        YearMonth now = YearMonth.from(LocalDate.now());
        List<SalaryAdvanceInstallment> schedule = installmentRepo.findBySalaryAdvanceIdOrderBySeqAsc(a.getId());
        String shifted = null;
        if (schedule.isEmpty()) {
            // Approved before V39: no schedule yet, built now as it used to be.
            YearMonth first = a.getFirstDeductionMonth() == null ? now : YearMonth.from(a.getFirstDeductionMonth());
            if (first.isBefore(now)) {
                shifted = "Première retenue reportée de " + monthLabel(first) + " à " + monthLabel(now) + " (versement tardif)";
                first = now;
            }
            a.setFirstDeductionMonth(first.atDay(1));
            installmentRepo.saveAll(buildSchedule(a.getId(), a.getAmount(), a.getInstallments(), first));
        } else {
            YearMonth first = schedule.get(0).period();
            if (first.isBefore(now)) {
                long delta = ChronoUnit.MONTHS.between(first, now);
                schedule.forEach(line -> line.setPeriod(line.period().plusMonths(delta)));
                installmentRepo.saveAll(schedule);
                a.setFirstDeductionMonth(now.atDay(1));
                shifted = "Échéancier décalé de " + delta + " mois : première retenue en " + monthLabel(now) + " (versement tardif)";
            }
        }

        a.setDisbursementMethod(method);
        a.setDisbursementReference(trimToNull(req.reference()));
        a.setDisbursedOn(req.disbursedOn());
        a.setDisbursedBy(callerId);
        a.setDisbursedAt(OffsetDateTime.now());
        if (a.getOutstandingAmount() == null) a.setOutstandingAmount(a.getAmount());

        String notes = join("Versement " + a.getAmount().toPlainString() + " " + a.getCurrency()
                + " (" + method + ") le " + req.disbursedOn().format(FR_DATE), join(shifted, trimToNull(req.notes())));
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
                  "Retenue de " + monthLabel(line.period()) + " non prélevée, reportée à " + monthLabel(next) + " : " + req.notes().trim());
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
        p.setDirectorThreshold(dto.directorThreshold() == null ? null : dto.directorThreshold().setScale(3, RoundingMode.HALF_UP));
        if (dto.requireSignedContract() != null) p.setRequireSignedContract(dto.requireSignedContract());
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
                months.add(monthLabel(line.period()));
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
                throw new IllegalStateException("La mensualité " + monthLabel(line.period()) + " a déjà été traitée (" + line.getStatus() + ")");
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

    private SalaryAdvance requireDirectorStep(Long id, Long callerId) {
        SalaryAdvance a = loadInScope(id);
        assertNotOwn(a, callerId);
        requireStatus(a, "Cette avance n'attend pas de validation au-dessus du seuil", SalaryAdvanceStatus.PENDING_DIRECTOR);
        return a;
    }

    private SalaryAdvance requireFinanceStep(Long id, Long callerId) {
        SalaryAdvance a = loadInScope(id);
        assertNotOwn(a, callerId);
        requireStatus(a, "Cette avance n'attend pas de décision du DAF", SalaryAdvanceStatus.PENDING_FINANCE);
        return a;
    }

    private static void stampDirector(SalaryAdvance a, Long callerId, String notes) {
        a.setDirectorDecidedBy(callerId);
        a.setDirectorDecidedAt(OffsetDateTime.now());
        a.setDirectorNotes(trimToNull(notes));
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
     * The variables feed both the in-app text and the e-mails (rh V103 / V128).
     */
    private void notifyAfterCommit(String event, SalaryAdvance a, String notes) {
        Map<String, String> vars = new HashMap<>();
        vars.put("employeeName", Objects.toString(names(List.of(a.getProfileUserId())).get(a.getProfileUserId()), ""));
        vars.put("amount", a.getAmount().toPlainString());
        vars.put("currency", a.getCurrency());
        vars.put("installments", String.valueOf(a.getInstallments()));
        vars.put("monthlyAmount", monthlyAmount(a.getAmount(), a.getInstallments()).toPlainString());
        vars.put("requestedAmount", a.getRequestedAmount() == null ? a.getAmount().toPlainString() : a.getRequestedAmount().toPlainString());
        vars.put("requestedInstallments", String.valueOf(a.getRequestedInstallments() == null ? a.getInstallments() : a.getRequestedInstallments()));
        vars.put("availabilityDate", a.getRequestedAvailabilityDate() == null ? "" : a.getRequestedAvailabilityDate().format(FR_DATE));
        vars.put("effectiveDate", a.getEffectiveDate() == null ? "" : a.getEffectiveDate().format(FR_DATE));
        vars.put("firstMonth", a.getFirstDeductionMonth() == null ? "" : monthLabel(YearMonth.from(a.getFirstDeductionMonth())));
        vars.put("reason", a.getReason() == null ? "—" : a.getReason());
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

    private static String monthLabel(YearMonth m) {
        return m.atDay(1).format(FR_MONTH);
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
        Map<Long, SalaryAdvancePolicy> policies = policies();
        return advances.stream().map(a -> map(a, schedules.getOrDefault(a.getId(), List.of()), names,
                List.of(), policies.get(a.getPaysId()))).toList();
    }

    private AdvanceDto toDto(SalaryAdvance a, boolean withHistory) {
        List<SalaryAdvanceInstallment> schedule = installmentRepo.findBySalaryAdvanceIdOrderBySeqAsc(a.getId());
        List<SalaryAdvanceHistory> history = withHistory ? historyRepo.findBySalaryAdvanceIdOrderByCreatedAtAsc(a.getId()) : List.of();
        Set<Long> userIds = new LinkedHashSet<>();
        addUsers(userIds, a);
        history.forEach(h -> { if (h.getActorUserId() != null) userIds.add(h.getActorUserId()); });
        return map(a, schedule, names(userIds), history, policyRepo.findByPaysId(a.getPaysId()).orElse(null));
    }

    private Map<Long, SalaryAdvancePolicy> policies() {
        Map<Long, SalaryAdvancePolicy> byPays = new HashMap<>();
        policyRepo.findAllByOrderByPaysIdAsc().forEach(p -> byPays.put(p.getPaysId(), p));
        return byPays;
    }

    private AdvanceDto map(SalaryAdvance a, List<SalaryAdvanceInstallment> schedule, Map<Long, String> names,
                           List<SalaryAdvanceHistory> history, SalaryAdvancePolicy policy) {
        return new AdvanceDto(
                a.getId(), a.getPaysId(), a.getProfileUserId(), names.get(a.getProfileUserId()),
                a.getCurrency(), a.getAmount(), a.getInstallments(),
                a.getFirstDeductionMonth() == null ? null : YearMonth.from(a.getFirstDeductionMonth()).toString(),
                schedule.isEmpty() ? null : schedule.get(schedule.size() - 1).period().toString(),
                monthlyAmount(a.getAmount(), a.getInstallments()),
                a.getRequestedAmount() != null ? a.getRequestedAmount() : a.getAmount(),
                a.getRequestedInstallments() != null ? a.getRequestedInstallments() : a.getInstallments(),
                a.getRequestedAvailabilityDate(), a.getEffectiveDate(),
                a.getReason(), a.getStatus(),
                policy == null ? null : policy.getDirectorThreshold(),
                policy == null || !Boolean.FALSE.equals(policy.getRequireSignedContract()),
                names.get(a.getDirectorDecidedBy()), a.getDirectorDecidedAt(), a.getDirectorNotes(),
                names.get(a.getFinanceDecidedBy()), a.getFinanceDecidedAt(), a.getFinanceNotes(),
                a.getContractStatus(), a.getContractDocumentId() != null, a.getContractGeneratedAt(),
                a.getSignedContractDocumentId() != null, names.get(a.getSignedContractUploadedBy()),
                a.getSignedContractUploadedAt(),
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
                p.getIsActive(), p.getDirectorThreshold(), !Boolean.FALSE.equals(p.getRequireSignedContract()),
                p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt());
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
        for (Long id : new Long[] { a.getProfileUserId(), a.getDirectorDecidedBy(), a.getFinanceDecidedBy(),
                                    a.getSignedContractUploadedBy(), a.getDisbursedBy() }) {
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
