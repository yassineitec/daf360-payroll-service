package com.daf360.payroll.modules.ref.controller;

import com.daf360.payroll.modules.ref.entity.PaysRef;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.PaysRefRepository;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.ProfileSyncService;
import com.daf360.payroll.security.PermissionCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payroll/ref")
public class RefController {

    private final PaysRefRepository paysRefRepository;
    private final UsersRefRepository usersRefRepository;
    private final ProfileSyncService profileSyncService;

    public RefController(PaysRefRepository paysRefRepository,
                         UsersRefRepository usersRefRepository,
                         ProfileSyncService profileSyncService) {
        this.paysRefRepository = paysRefRepository;
        this.usersRefRepository = usersRefRepository;
        this.profileSyncService = profileSyncService;
    }

    @GetMapping("/pays")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<PaysRef> listPays() {
        return paysRefRepository.findAll();
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<UsersRef> listUsers() {
        // The replica deliberately keeps test and machine accounts (other tables reference
        // users_ref), so this picker is where they are excluded.
        return usersRefRepository.findByIsEmployeeTrueOrderByFullNameAsc();
    }

    @GetMapping("/users/by-pays/{paysId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<UsersRef> usersByPays(@PathVariable Long paysId) {
        // Same exclusion as /users. It was missing here, which made this endpoint a way back in
        // for exactly the accounts the other one hides.
        return usersRefRepository.findByPaysIdAndIsEmployeeTrueOrderByFullNameAsc(paysId);
    }

    @PostMapping("/sync")
    /*
     * UPDATE_USER accepte en plus de PAYROLL_SUPER_ADMIN, et ce n'est pas un relachement
     * de garde : c'est un rafraichissement de referentiel, idempotent, qui ne fait que
     * relire le RH. Il n'ecrit aucune donnee de paie et n'en expose aucune.
     *
     * Sans cela, la propagation declenchee depuis Administration → Utilisateurs (RH)
     * repondait 403 : un administrateur RH detient UPDATE_USER, pas PAYROLL_SUPER_ADMIN, et
     * lui donner ce dernier pour un resync serait disproportionne — c'est le code qui ouvre
     * toute l'administration de la paie et contourne l'isolation par pays
     * (cf. PaysIsolationInterceptor).
     *
     * UPDATE_USER est un code RH, absent du PermissionCatalog de ce module. C'est volontaire
     * et sans risque : les autorites du jeton sont globales (mintees par le portail depuis
     * RolePermissions), et la regle exprimee est exactement la bonne — qui peut modifier un
     * compte peut pousser cette modification.
     */
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.SUPER_ADMIN + "', 'UPDATE_USER')")
    public ResponseEntity<Void> triggerSync() {
        profileSyncService.sync();
        return ResponseEntity.noContent().build();
    }
}
