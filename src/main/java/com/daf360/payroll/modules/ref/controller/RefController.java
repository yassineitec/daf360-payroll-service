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
        return usersRefRepository.findAll();
    }

    @GetMapping("/users/by-pays/{paysId}")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<UsersRef> usersByPays(@PathVariable Long paysId) {
        return usersRefRepository.findByPaysId(paysId);
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SUPER_ADMIN + "')")
    public ResponseEntity<Void> triggerSync() {
        profileSyncService.sync();
        return ResponseEntity.noContent().build();
    }
}
