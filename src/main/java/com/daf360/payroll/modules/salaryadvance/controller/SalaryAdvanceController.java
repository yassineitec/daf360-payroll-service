package com.daf360.payroll.modules.salaryadvance.controller;

import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.salaryadvance.dto.SalaryAdvanceDtos.*;
import com.daf360.payroll.modules.salaryadvance.entity.InstallmentStatus;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceStatus;
import com.daf360.payroll.modules.salaryadvance.service.SalaryAdvanceService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Salary advances — payroll owns them, because they exist to be deducted from the next salaries.
 *
 * Every desk is a PERMISSION, never a role: any role holding the code can act.
 *  - the employee, from self-service — the {@code /my*} endpoints carry NO permission: everyone
 *    files and reads their own, and the service checks the row belongs to the caller;
 *  - above the country threshold — {@code FACT_VALIDATE_SALARY_ADVANCE_ABOVE_THRESHOLD};
 *  - the DAF — {@code FACT_APPROVE_SALARY_ADVANCE}: final terms, decision, signed contract;
 *  - read-only — {@code FACT_VIEW_SALARY_ADVANCES}: the « Avances sur salaire » page, contracts;
 *  - payroll — {@code PAYROLL_MANAGE_SALARY_ADVANCES}: payout, monthly deductions, rules.
 * FACT_ codes are declared in FactPermissionCatalog; the finance frontend calls this service
 * on its payroll API URL.
 */
@Slf4j
@RestController
@RequestMapping("/api/payroll/salary-advances")
public class SalaryAdvanceController {

    private static final String DIRECTOR = "hasAuthority('FACT_VALIDATE_SALARY_ADVANCE_ABOVE_THRESHOLD')";
    private static final String FINANCE  = "hasAuthority('FACT_APPROVE_SALARY_ADVANCE')";
    private static final String PAYROLL  = "hasAuthority('PAYROLL_MANAGE_SALARY_ADVANCES')";
    /** Everyone who reads advances: the page, the detail, the contracts. */
    private static final String READ     = "hasAnyAuthority('FACT_VIEW_SALARY_ADVANCES','FACT_APPROVE_SALARY_ADVANCE',"
            + "'FACT_VALIDATE_SALARY_ADVANCE_ABOVE_THRESHOLD','PAYROLL_MANAGE_SALARY_ADVANCES')";

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

    // ── Director — above the country threshold ──────────────────────────────

    @GetMapping("/pending-director")
    @PreAuthorize(DIRECTOR)
    public List<AdvanceDto> pendingDirector() {
        return service.listPendingDirector();
    }

    @PostMapping("/{id}/director-approve")
    @PreAuthorize(DIRECTOR)
    public AdvanceDto directorApprove(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.directorApprove(id, request.notes(), caller());
    }

    @PostMapping("/{id}/director-reject")
    @PreAuthorize(DIRECTOR)
    public AdvanceDto directorReject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.directorReject(id, request.notes(), caller());
    }

    // ── DAF — final terms, decision, contract ───────────────────────────────

    @GetMapping("/pending-finance")
    @PreAuthorize(FINANCE)
    public List<AdvanceDto> pendingFinance() {
        return service.listPendingFinance();
    }

    /**
     * Grants the advance with its final terms, then has RH generate the debt contract. The
     * two run in separate transactions on purpose: the approval stands even when RH is down —
     * the answer then shows no contract, and {@code POST /{id}/contract/generate} retries.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize(FINANCE)
    public AdvanceDto approve(@PathVariable Long id, @Valid @RequestBody ApproveRequest request) {
        Long caller = caller();
        AdvanceDto approved = service.approve(id, request, caller);
        // The grant is committed at this point. Whatever goes wrong with the contract, the
        // answer must be the approval: an error here made the DAF click again and get
        // « n'attend pas de décision du DAF (statut = APPROVED) » (TC-12). The page then
        // shows « Contrat non généré » and offers « Générer le contrat ».
        try {
            return service.generateContract(id, caller);
        } catch (RuntimeException ex) {
            log.warn("Advance {} approved, contract not generated: {}", id, ex.toString(), ex);
            return approved;
        }
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(FINANCE)
    public AdvanceDto reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.reject(id, request.notes(), caller());
    }

    /** (Re)generates the contract — after an RH outage at approval, or a template fix. */
    @PostMapping("/{id}/contract/generate")
    @PreAuthorize(FINANCE)
    public AdvanceDto generateContract(@PathVariable Long id) {
        return service.generateContract(id, caller());
    }

    /** The signed, legalised scan (PDF / JPG / PNG, 10 MB). RH archives it in the employee's folder. */
    @PostMapping(value = "/{id}/contract/signed", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FINANCE)
    public AdvanceDto uploadSignedContract(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        return service.uploadSignedContract(id, file.getBytes(), file.getOriginalFilename(), file.getContentType(), caller());
    }

    /** The generated contract, or the signed scan with {@code signed=true}. */
    @GetMapping("/{id}/contract")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> contract(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean signed) {
        ContractFile file = service.contract(id, signed);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    // ── Read — the « Avances sur salaire » page, payroll's follow-up ────────

    /** Every advance in scope. {@code status} may be repeated; absent = every status. */
    @GetMapping
    @PreAuthorize(READ)
    public List<AdvanceDto> list(@RequestParam(required = false) List<SalaryAdvanceStatus> status) {
        return service.listAll(status);
    }

    // ── Payroll — payout, deductions, rules ─────────────────────────────────

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
    @PreAuthorize(READ)
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
