package com.daf360.payroll.modules.parameterset.controller;

import com.daf360.payroll.modules.parameterset.dto.BenefitCatalogueDto;
import com.daf360.payroll.modules.parameterset.dto.CreateParameterSetRequest;
import com.daf360.payroll.modules.parameterset.dto.ParameterSetDto;
import com.daf360.payroll.modules.parameterset.dto.SavePayrollRubriqueRequest;
import com.daf360.payroll.modules.parameterset.dto.SocialChargeRateDto;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PaysScopeContext;
import com.daf360.payroll.security.PermissionCatalog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/parameter-sets")
public class ParameterSetController {

    private final ParameterSetService service;
    private final UserContextService userContext;

    public ParameterSetController(ParameterSetService service, UserContextService userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public List<ParameterSetDto> list(@RequestParam Long paysId) {
        return service.listByPays(paysId);
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public ParameterSetDto getActive(@RequestParam Long paysId) {
        return service.getActiveByPays(paysId);
    }

    /**
     * Un jeu par son id. Ouvert aussi à RUN_SIMULATION (comme `/active`) : la fiche d'une
     * simulation affiche les taux et le barème du jeu qui a servi au calcul
     * (`simulation.parameterSetId`) — un jeu ACTIF ou ARCHIVÉ n'est plus modifiable, ce sont
     * donc exactement ceux du calcul.
     *
     * Contrôle du pays ici : l'id passe dans le chemin, pas en `paysId`, donc le
     * `PaysIsolationInterceptor` ne le voit pas.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET
            + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public ParameterSetDto getById(@PathVariable Long id) {
        ParameterSetDto ps = service.getById(id);
        if (!isSuperAdmin()) {
            PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
            if (ps.paysId() == null || scope == null || !scope.allows(ps.paysId())) {
                throw new AccessDeniedException("Jeu de paramètres hors de votre périmètre pays");
            }
        }
        return ps;
    }

    private static boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "PAYROLL_SUPER_ADMIN".equals(a.getAuthority()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public ResponseEntity<ParameterSetDto> create(@RequestBody @Valid CreateParameterSetRequest req) {
        Long userId = userContext.currentUserId();
        ParameterSetDto created = service.create(req, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "')")
    public ParameterSetDto submit(@PathVariable Long id) {
        return service.submitForApproval(id);
    }

    @PostMapping("/{id}/approve/hr")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto approveHr(@PathVariable Long id) {
        Long userId = userContext.currentUserId();
        return service.approveHr(id, userId);
    }

    @PostMapping("/{id}/approve/finance")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto approveFinance(@PathVariable Long id) {
        Long userId = userContext.currentUserId();
        return service.approveFinance(id, userId);
    }

    @PutMapping("/{id}/social-charge-rates")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto updateSocialChargeRates(@PathVariable Long id,
            @RequestBody List<SocialChargeRateDto> rates) {
        return service.updateSocialChargeRates(id, rates);
    }

    /** Remplace les avantages en nature d'un jeu en brouillon (409 sinon). */
    @PutMapping("/{id}/benefits")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto updateBenefits(@PathVariable Long id,
            @RequestBody List<BenefitCatalogueDto> benefits) {
        return service.updateBenefits(id, benefits);
    }

    @PutMapping("/{id}/rubriques")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.APPROVE_PARAMSET_FAST_TRACK + "')")
    public ParameterSetDto updateRubriques(@PathVariable Long id,
            @RequestBody List<SavePayrollRubriqueRequest> rubriques) {
        return service.updateRubriques(id, rubriques);
    }
}
