package com.daf360.payroll.modules.admin.controller;

import com.daf360.payroll.modules.admin.dto.HelpFaqDto;
import com.daf360.payroll.modules.admin.dto.SaveHelpFaqRequest;
import com.daf360.payroll.modules.admin.service.PayrollHelpFaqService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Administration › Assistant & FAQ, and the published questions read by the help assistant —
 * same endpoints as `/api/hr/help-faq` (RH) and `/api/fact/help-faq` (Finance). Writes and the
 * full list need PAYROLL_SUPER_ADMIN; `/active` is open to every signed-in user, since the
 * assistant is open to everyone.
 */
@RestController
@RequestMapping("/api/payroll/help-faq")
@RequiredArgsConstructor
public class PayrollHelpFaqController {

    private static final String SUPER_ADMIN = "hasAuthority('" + PermissionCatalog.SUPER_ADMIN + "')";

    private final PayrollHelpFaqService service;
    private final UserContextService    userContext;

    /** Every question (admin section). */
    @GetMapping
    @PreAuthorize(SUPER_ADMIN)
    public List<HelpFaqDto> list() {
        return service.listAll();
    }

    /** Published questions (assistant) — any signed-in user. */
    @GetMapping("/active")
    public List<HelpFaqDto> listActive() {
        return service.listActive();
    }

    @PostMapping
    @PreAuthorize(SUPER_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public HelpFaqDto create(@Valid @RequestBody SaveHelpFaqRequest req) {
        return service.create(req, userContext.currentUserId());
    }

    @PutMapping("/{id}")
    @PreAuthorize(SUPER_ADMIN)
    public HelpFaqDto update(@PathVariable Long id, @Valid @RequestBody SaveHelpFaqRequest req) {
        return service.update(id, req, userContext.currentUserId());
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize(SUPER_ADMIN)
    public HelpFaqDto activate(@PathVariable Long id) {
        return service.setActive(id, true, userContext.currentUserId());
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize(SUPER_ADMIN)
    public HelpFaqDto deactivate(@PathVariable Long id) {
        return service.setActive(id, false, userContext.currentUserId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(SUPER_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
