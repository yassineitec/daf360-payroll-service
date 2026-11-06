package com.daf360.payroll.modules.ref.controller;

import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.ref.entity.PaysRef;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.PaysRefRepository;
import com.daf360.payroll.modules.ref.repository.PayrollFxRateRepository;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.ProfileSyncService;
import com.daf360.payroll.security.PermissionCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payroll/ref")
public class RefController {

    private final PaysRefRepository paysRefRepository;
    private final UsersRefRepository usersRefRepository;
    private final ProfileSyncService profileSyncService;
    private final PayrollFxRateRepository fxRateRepository;
    private final PayrollCountryRepository payrollCountryRepository;

    public RefController(PaysRefRepository paysRefRepository,
                         UsersRefRepository usersRefRepository,
                         ProfileSyncService profileSyncService,
                         PayrollFxRateRepository fxRateRepository,
                         PayrollCountryRepository payrollCountryRepository) {
        this.paysRefRepository = paysRefRepository;
        this.usersRefRepository = usersRefRepository;
        this.profileSyncService = profileSyncService;
        this.fxRateRepository = fxRateRepository;
        this.payrollCountryRepository = payrollCountryRepository;
    }

    @GetMapping("/pays")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<PaysRef> listPays() {
        return paysRefRepository.findAll();
    }

    /**
     * Les pays du référentiel configurés et actifs dans le moteur de paie (carte « Pays de
     * paie » de /payroll/admin) — ceux qu'on propose dans les écrans de paie. Ouvert à
     * chaque code qui donne accès à un de ces écrans (résultats, moteur, calibration,
     * budget, configuration salarié, fiches de paie…) : ce ne sont que des libellés de
     * référentiel, pas la configuration elle-même. RH_MANAGE_PAYSLIPS est un code RH,
     * absent du PermissionCatalog (même cas qu'UPDATE_USER sur /sync).
     */
    @GetMapping("/pays/payroll")
    @PreAuthorize("hasAnyAuthority('"
        + PermissionCatalog.VIEW_PARAMSET + "','"
        + PermissionCatalog.RUN_SIMULATION + "','"
        + PermissionCatalog.VIEW_RESULTS + "','"
        + PermissionCatalog.RUN_ENGINE + "','"
        + PermissionCatalog.RUN_CALIBRATION + "','"
        + PermissionCatalog.VIEW_BUDGET_AGGREGATE + "','"
        + PermissionCatalog.VIEW_AGGREGATE + "','"
        + PermissionCatalog.EXPORT_BUDGET + "','"
        + PermissionCatalog.VIEW_EMPLOYEE_CONFIG + "','"
        + PermissionCatalog.MANAGE_EMPLOYEE_CONFIG + "','"
        + PermissionCatalog.MANAGE_COUNTRIES + "','"
        + PermissionCatalog.SUPER_ADMIN + "', 'RH_MANAGE_PAYSLIPS')")
    public List<PaysRef> listPayrollPays() {
        Set<Long> active = payrollCountryRepository.findByActiveTrue().stream()
                .map(PayrollCountry::getPaysId)
                .collect(Collectors.toSet());
        return paysRefRepository.findAllById(active).stream()
                .sorted(Comparator.comparing(PaysRef::getFrenchLabel, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<UsersRef> listUsers() {
        // The replica deliberately keeps test and machine accounts (other tables reference
        // users_ref), so this picker is where they are excluded.
        return usersRefRepository.findByIsEmployeeTrueOrderByFullNameAsc();
    }

    public record FxRateDto(String currencyCode, java.math.BigDecimal rateToEur) {}

    /** Currencies the simulator's amount field can offer, with their cached rate-to-EUR --
     *  the frontend never does its own FX math, it only needs the list of available codes. */
    @GetMapping("/fx-rates")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.VIEW_PARAMSET + "','" + PermissionCatalog.RUN_SIMULATION + "')")
    public List<FxRateDto> listFxRates() {
        return fxRateRepository.findAll().stream()
                .map(r -> new FxRateDto(r.getCurrencyCode(), r.getRateToEur()))
                .toList();
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
