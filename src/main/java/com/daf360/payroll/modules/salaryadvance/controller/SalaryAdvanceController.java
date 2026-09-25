package com.daf360.payroll.modules.salaryadvance.controller;

import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.salaryadvance.dto.SalaryAdvanceDtos.*;
import com.daf360.payroll.modules.salaryadvance.entity.InstallmentStatus;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceStatus;
import com.daf360.payroll.modules.salaryadvance.service.SalaryAdvanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Salary advances — payroll owns them, because they exist to be deducted from the next salaries.
 *
 * Three audiences, one controller because they act on one row:
 *  - the employee, from self-service — the {@code /my*} endpoints carry NO permission: everyone
 *    files and reads their own, and the service checks the row belongs to the caller;
 *  - finance, the decision hub — {@code FACT_APPROVE_SALARY_ADVANCE} (a FACT_ code, declared in
 *    FactPermissionCatalog; the finance frontend calls this service on its payroll API URL);
 *  - payroll — {@code PAYROLL_MANAGE_SALARY_ADVANCES}: payout, monthly deductions, follow-up, rules.
 */
@RestController
@RequestMapping("/api/payroll/salary-advances")
public class SalaryAdvanceController {

    private static final String FINANCE = "hasAuthority('FACT_APPROVE_SALARY_ADVANCE')";
    private static final String PAYROLL = "hasAuthority('PAYROLL_MANAGE_SALARY_ADVANCES')";
    /** The detail is read by both desks. */
    private static final String EITHER  = "hasAnyAuthority('FACT_APPROVE_SALARY_ADVANCE','PAYROLL_MANAGE_SALARY_ADVANCES')";

    private final SalaryAdvanceService service;
    private final UserContextService   userContext;

    public SalaryAdvanceController(SalaryAdvanceService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    // ── Employee (self-service) ─────────────────────────────────────────────

    /** May the caller ask, over how long. Drives the self-service modal. */
    @GetMapping("/my/eligibility")
    public Eligibility eligibility() {
        return service.eligibility(caller());
    }

    @GetMapping("/my")
    public List<AdvanceDto> my() {
        return service.listMine(caller());
    }

    @GetMapping("/my/{id}")
    public AdvanceDto myDetail(@PathVariable Long id) {
        return service.getMine(id, caller());
    }

    @PostMapping("/my")
    @ResponseStatus(HttpStatus.CREATED)
    public AdvanceDto submit(@Valid @RequestBody SubmitRequest request) {
        return service.submit(request, caller());
    }

    @PostMapping("/my/{id}/cancel")
    public AdvanceDto cancel(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.cancelMine(id, request.notes(), caller());
    }

    // ── Finance — approve / decline ─────────────────────────────────────────

    @GetMapping("/pending-finance")
    @PreAuthorize(FINANCE)
    public List<AdvanceDto> pendingFinance() {
        return service.listPendingFinance();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(FINANCE)
    public AdvanceDto approve(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.approve(id, request.notes(), caller());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(FINANCE)
    public AdvanceDto reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.reject(id, request.notes(), caller());
    }

    // ── Payroll — payout, deductions, follow-up, rules ──────────────────────

    /** The follow-up list. {@code status} may be repeated; absent = every status. */
    @GetMapping
    @PreAuthorize(PAYROLL)
    public List<AdvanceDto> list(@RequestParam(required = false) List<SalaryAdvanceStatus> status) {
        return service.listAll(status);
    }

    @GetMapping("/to-disburse")
    @PreAuthorize(PAYROLL)
    public List<AdvanceDto> toDisburse() {
        return service.listToDisburse();
    }

    @PostMapping("/{id}/disburse")
    @PreAuthorize(PAYROLL)
    public AdvanceDto disburse(@PathVariable Long id, @Valid @RequestBody DisburseRequest request) {
        return service.disburse(id, request, caller());
    }

    /** One employee's advances — the employee payroll configuration page. */
    @GetMapping("/employee/{profileUserId}")
    @PreAuthorize(PAYROLL)
    public List<AdvanceDto> forEmployee(@PathVariable Long profileUserId) {
        return service.listForEmployee(profileUserId);
    }

    /** The month's deductions, every status — the screen shows what is already ticked off. */
    @GetMapping("/deductions")
    @PreAuthorize(PAYROLL)
    public List<DeductionRowDto> deductions(@RequestParam String month) {
        return service.deductions(parseMonth(month));
    }

    /**
     * The same list as CSV for the payroll software — PLANNED lines only. Semicolon-separated
     * with a BOM, so Excel (fr) opens it with the accents and the columns right.
     */
    @GetMapping("/deductions/export")
    @PreAuthorize(PAYROLL)
    public ResponseEntity<byte[]> exportDeductions(@RequestParam String month) {
        YearMonth ym = parseMonth(month);
        StringBuilder csv = new StringBuilder().append('\uFEFF');
        csv.append("Matricule;Collaborateur;Mois;Mensualite;Montant;Devise;Reste du;Avance\n");
        for (DeductionRowDto row : service.deductions(ym)) {
            if (row.status() != InstallmentStatus.PLANNED) continue;
            csv.append(cell(row.payrollMatricule())).append(';')
               .append(cell(row.employeeName())).append(';')
               .append(row.month()).append(';')
               .append(row.seq()).append('/').append(row.installmentsTotal()).append(';')
               .append(row.amount().toPlainString().replace('.', ',')).append(';')
               .append(row.currency()).append(';')
               .append(row.outstandingAmount() == null ? "" : row.outstandingAmount().toPlainString().replace('.', ','))
               .append(';')
               .append(row.salaryAdvanceId()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"retenues-avances-" + ym + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/installments/deducted")
    @PreAuthorize(PAYROLL)
    public Map<String, Integer> markDeducted(@Valid @RequestBody InstallmentActionRequest request) {
        return Map.of("updated", service.markDeducted(request, caller()));
    }

    @PostMapping("/installments/skip")
    @PreAuthorize(PAYROLL)
    public Map<String, Integer> skip(@Valid @RequestBody InstallmentActionRequest request) {
        return Map.of("updated", service.skip(request, caller()));
    }

    @PostMapping("/installments/waive")
    @PreAuthorize(PAYROLL)
    public Map<String, Integer> waive(@Valid @RequestBody InstallmentActionRequest request) {
        return Map.of("updated", service.waive(request, caller()));
    }

    @GetMapping("/policies")
    @PreAuthorize(PAYROLL)
    public List<PolicyDto> policies() {
        return service.listPolicies();
    }

    @PutMapping("/policies/{paysId}")
    @PreAuthorize(PAYROLL)
    public PolicyDto savePolicy(@PathVariable Long paysId, @Valid @RequestBody PolicyDto request) {
        return service.savePolicy(paysId, request, caller());
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize(EITHER)
    public AdvanceDto detail(@PathVariable Long id) {
        return service.getById(id);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The caller's RH user id. No fallback: the id decides whose advance may be read,
     * withdrawn or decided, so an unknown caller is refused rather than guessed.
     */
    private Long caller() {
        Long id = userContext.currentUserId();
        if (id == null) throw new AccessDeniedException("Utilisateur non identifié");
        return id;
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Mois invalide (attendu : AAAA-MM) : " + month);
        }
    }

    private static String cell(String value) {
        if (value == null) return "";
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
