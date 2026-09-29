package com.daf360.payroll.modules.admin.controller;

import com.daf360.payroll.modules.admin.dto.SaveEngineParamSetRequest;
import com.daf360.payroll.modules.admin.dto.SaveEngineRubriqueRequest;
import com.daf360.payroll.modules.admin.service.EngineConfigService;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Écritures de l'administration paie sur la configuration du moteur (cartes « Paramètres
 * du moteur » et « Rubriques du moteur »). Lecture des versions et circuit de validation :
 * endpoints existants de {@code PayrollEngineController} (`/engine/param-sets…`).
 *
 * `paysId` en paramètre d'URL là où il identifie le pays : `PaysIsolationInterceptor`
 * applique alors le périmètre ; les appels par identifiant le vérifient dans le service.
 */
@RestController
@RequestMapping("/api/payroll/admin/engine")
@RequiredArgsConstructor
public class EngineConfigAdminController {

    private static final String EDIT_PARAMSETS =
        "hasAnyAuthority('" + PermissionCatalog.APPROVE_PARAMSET + "','" + PermissionCatalog.SUPER_ADMIN + "')";
    private static final String MANAGE_RUBRIQUES =
        "hasAnyAuthority('" + PermissionCatalog.MANAGE_RUBRIQUES + "','" + PermissionCatalog.SUPER_ADMIN + "')";

    private final EngineConfigService service;
    private final UserContextService  userContext;

    /** Nouvelle version en brouillon pour un pays (copie de la version active). */
    @PostMapping("/param-sets")
    @PreAuthorize(EDIT_PARAMSETS)
    public ResponseEntity<PayrollParamSet> createDraft(@RequestParam Long paysId) {
        String by = userContext.currentUser().map(UsersRef::getFullName)
                .orElseGet(() -> String.valueOf(userContext.currentUserId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDraft(paysId, by));
    }

    /** Modifie les paramètres d'une version en brouillon. */
    @PutMapping("/param-sets/{id}")
    @PreAuthorize(EDIT_PARAMSETS)
    public PayrollParamSet updateDraft(@PathVariable Long id, @RequestBody SaveEngineParamSetRequest req) {
        return service.updateDraft(id, req);
    }

    /** Toutes les rubriques du pays, actives ou non. */
    @GetMapping("/rubriques")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.MANAGE_RUBRIQUES + "','"
        + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.SUPER_ADMIN + "')")
    public List<PayrollRubriqueDef> listRubriques(@RequestParam Long paysId) {
        return service.listRubriques(paysId);
    }

    @PostMapping("/rubriques")
    @PreAuthorize(MANAGE_RUBRIQUES)
    public ResponseEntity<PayrollRubriqueDef> createRubrique(@RequestParam Long paysId,
                                                             @RequestBody SaveEngineRubriqueRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRubrique(paysId, req));
    }

    @PutMapping("/rubriques/{id}")
    @PreAuthorize(MANAGE_RUBRIQUES)
    public PayrollRubriqueDef updateRubrique(@PathVariable Long id, @RequestBody SaveEngineRubriqueRequest req) {
        return service.updateRubrique(id, req);
    }
}
