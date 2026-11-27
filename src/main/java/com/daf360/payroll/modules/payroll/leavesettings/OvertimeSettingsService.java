package com.daf360.payroll.modules.payroll.leavesettings;

import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import com.daf360.payroll.modules.payroll.variable.HrPayrollInputsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Weekend-overtime rules (V43), read by the payroll run and edited in the payroll screens.
 *
 * <h3>Majoration — per country and weekend day</h3>
 * {@code payroll_overtime_day_rates} (e.g. SATURDAY 50 %, SUNDAY 100 %), set in Paie ›
 * Administration › « Absences, HS et période de paie ». A day without a value uses the engine
 * parameter {@link #PARAM_MAJORATION} of the country's ACTIVE parameter set (« Taux et
 * barèmes »). Neither = not configured (the run pays the hours without majoration and warns).
 *
 * <h3>Daily cap — most specific first</h3>
 * the employee's own cap (« Salaires des collaborateurs »), else the cap of their RH role in that
 * country (administration), else the engine parameter {@link #PARAM_DAILY_CAP}, else
 * {@link #DEFAULT_DAILY_CAP} hours.
 */
@Service
public class OvertimeSettingsService {

    public static final String PARAM_MAJORATION = "taux_majoration_hs_weekend";
    public static final String PARAM_DAILY_CAP = "plafond_hs_jour";
    public static final BigDecimal DEFAULT_DAILY_CAP = BigDecimal.valueOf(8);

    /** Where the cap of a run came from — shown on the payslip. */
    public enum CapSource { EMPLOYEE, ROLE, COUNTRY, DEFAULT }

    private final OvertimeDayRateRepository dayRateRepo;
    private final OvertimeCapRepository capRepo;
    private final PayrollCountryRepository countryRepo;
    private final PayrollParamSetRepository paramSetRepo;
    private final HrPayrollInputsClient hrClient;
    private final ObjectMapper objectMapper;

    public OvertimeSettingsService(OvertimeDayRateRepository dayRateRepo, OvertimeCapRepository capRepo,
                                   PayrollCountryRepository countryRepo, PayrollParamSetRepository paramSetRepo,
                                   HrPayrollInputsClient hrClient, ObjectMapper objectMapper) {
        this.dayRateRepo = dayRateRepo;
        this.capRepo = capRepo;
        this.countryRepo = countryRepo;
        this.paramSetRepo = paramSetRepo;
        this.hrClient = hrClient;
        this.objectMapper = objectMapper;
    }

    // ── Read by the payroll run ─────────────────────────────────────────────────────────────

    /**
     * The rules for one employee's run. {@code params} = the ACTIVE parameter set of the run
     * (already loaded by the engine); {@code roleId} = their RH role (null = unknown).
     */
    @Transactional(readOnly = true)
    public OvertimeRules rulesFor(Long paysId, Long roleId, Long employeeId, Map<String, Object> params) {
        BigDecimal defaultPct = decimalParam(params, PARAM_MAJORATION);
        Map<DayOfWeek, BigDecimal> perDay = new EnumMap<>(DayOfWeek.class);
        if (paysId != null) {
            for (OvertimeDayRate r : dayRateRepo.findByPaysId(paysId)) {
                try { perDay.put(DayOfWeek.valueOf(r.getDayOfWeek()), r.getMajorationPct()); }
                catch (IllegalArgumentException ignored) { /* CHECK constraint keeps this out */ }
            }
        }

        Optional<OvertimeCap> employee = employeeId == null ? Optional.empty()
                : capRepo.findByScopeAndProfileUserId(OvertimeCap.EMPLOYEE, employeeId);
        if (employee.isPresent()) {
            return new OvertimeRules(perDay, defaultPct, employee.get().getCapHours(), CapSource.EMPLOYEE);
        }
        Optional<OvertimeCap> role = paysId == null || roleId == null ? Optional.empty()
                : capRepo.findByScopeAndPaysIdAndRoleId(OvertimeCap.ROLE, paysId, roleId);
        if (role.isPresent()) {
            return new OvertimeRules(perDay, defaultPct, role.get().getCapHours(), CapSource.ROLE);
        }
        BigDecimal countryCap = decimalParam(params, PARAM_DAILY_CAP);
        if (countryCap != null && countryCap.signum() > 0) {
            return new OvertimeRules(perDay, defaultPct, countryCap, CapSource.COUNTRY);
        }
        return new OvertimeRules(perDay, defaultPct, DEFAULT_DAILY_CAP, CapSource.DEFAULT);
    }

    /**
     * Engine parameters are read case-insensitively: « Taux et barèmes » stores every key it
     * creates in capitals (TAUX_MAJORATION_HS_WEEKEND), seeds and scripts wrote lower case.
     */
    public static BigDecimal decimalParam(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object raw = params.get(key);
        if (raw == null) {
            raw = params.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(key))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
        if (raw == null) return null;
        try {
            return raw instanceof BigDecimal b ? b : new BigDecimal(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Administration (per country) ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminView adminView(Long paysId) {
        requirePays(paysId);
        Map<String, Object> params = activeParams(paysId);
        BigDecimal defaultPct = decimalParam(params, PARAM_MAJORATION);
        BigDecimal paramCap = decimalParam(params, PARAM_DAILY_CAP);
        BigDecimal countryCap = paramCap != null && paramCap.signum() > 0 ? paramCap : DEFAULT_DAILY_CAP;

        Optional<HrPayrollInputsClient.SettingsRef> ref = hrClient.settingsRef(paysId, null);
        List<String> weekend = ref.map(HrPayrollInputsClient.SettingsRef::weekendDays)
                .filter(l -> !l.isEmpty()).orElse(List.of("SATURDAY", "SUNDAY"));

        Map<String, BigDecimal> rates = new HashMap<>();
        dayRateRepo.findByPaysId(paysId).forEach(r -> rates.put(r.getDayOfWeek(), r.getMajorationPct()));
        List<DayRate> days = new ArrayList<>();
        for (String d : weekend) {
            BigDecimal own = rates.get(d);
            days.add(new DayRate(d, own, own != null ? own : defaultPct));
        }

        Map<Long, BigDecimal> caps = new HashMap<>();
        capRepo.findByScopeAndPaysId(OvertimeCap.ROLE, paysId).forEach(c -> caps.put(c.getRoleId(), c.getCapHours()));
        List<RoleCap> roles = new ArrayList<>();
        for (HrPayrollInputsClient.RoleRef r : ref.map(HrPayrollInputsClient.SettingsRef::roles).orElse(List.of())) {
            roles.add(new RoleCap(r.id(), r.labelFr(), r.labelEn(), caps.get(r.id())));
        }
        return new AdminView(paysId, ref.isPresent(), days, defaultPct, countryCap, paramCap != null && paramCap.signum() > 0, roles);
    }

    @Transactional
    public AdminView saveDayRate(Long paysId, String day, BigDecimal pct, Long caller) {
        requirePays(paysId);
        String d = day == null ? "" : day.trim().toUpperCase();
        try { DayOfWeek.valueOf(d); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Jour inconnu : " + day); }
        Optional<OvertimeDayRate> existing = dayRateRepo.findByPaysIdAndDayOfWeek(paysId, d);
        if (pct == null) {
            existing.ifPresent(dayRateRepo::delete);
        } else {
            if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(500)) > 0) {
                throw new IllegalArgumentException("La majoration doit être comprise entre 0 et 500 %.");
            }
            OvertimeDayRate r = existing.orElseGet(() -> {
                OvertimeDayRate n = new OvertimeDayRate();
                n.setPaysId(paysId);
                n.setDayOfWeek(d);
                return n;
            });
            r.setMajorationPct(pct);
            r.setUpdatedBy(caller);
            dayRateRepo.save(r);
        }
        return adminView(paysId);
    }

    @Transactional
    public AdminView saveRoleCap(Long paysId, Long roleId, BigDecimal hours, Long caller) {
        requirePays(paysId);
        if (roleId == null || roleId <= 0) throw new IllegalArgumentException("Rôle obligatoire.");
        Optional<OvertimeCap> existing = capRepo.findByScopeAndPaysIdAndRoleId(OvertimeCap.ROLE, paysId, roleId);
        if (hours == null) {
            existing.ifPresent(capRepo::delete);
        } else {
            checkHours(hours);
            OvertimeCap c = existing.orElseGet(() -> {
                OvertimeCap n = new OvertimeCap();
                n.setScope(OvertimeCap.ROLE);
                n.setPaysId(paysId);
                n.setRoleId(roleId);
                return n;
            });
            c.setCapHours(hours);
            c.setUpdatedBy(caller);
            capRepo.save(c);
        }
        return adminView(paysId);
    }

    // ── One employee (« Salaires des collaborateurs ») ─────────────────────────────────────

    @Transactional(readOnly = true)
    public EmployeeView employeeView(Long userId) {
        if (userId == null) throw new IllegalArgumentException("Collaborateur obligatoire.");
        Optional<HrPayrollInputsClient.SettingsRef> ref = hrClient.settingsRef(null, userId);
        Long paysId = ref.map(HrPayrollInputsClient.SettingsRef::employeePaysId).orElse(null);
        Long roleId = ref.map(HrPayrollInputsClient.SettingsRef::employeeRoleId).orElse(null);
        String roleLabel = null;
        if (roleId != null) {
            roleLabel = ref.get().roles().stream().filter(r -> r.id() == roleId)
                    .map(HrPayrollInputsClient.RoleRef::labelFr).findFirst().orElse(null);
        }
        BigDecimal own = capRepo.findByScopeAndProfileUserId(OvertimeCap.EMPLOYEE, userId)
                .map(OvertimeCap::getCapHours).orElse(null);
        BigDecimal roleCap = paysId == null || roleId == null ? null
                : capRepo.findByScopeAndPaysIdAndRoleId(OvertimeCap.ROLE, paysId, roleId).map(OvertimeCap::getCapHours).orElse(null);
        BigDecimal paramCap = paysId == null ? null : decimalParam(activeParams(paysId), PARAM_DAILY_CAP);
        BigDecimal countryCap = paramCap != null && paramCap.signum() > 0 ? paramCap : DEFAULT_DAILY_CAP;
        CapSource source = own != null ? CapSource.EMPLOYEE : roleCap != null ? CapSource.ROLE
                : paramCap != null && paramCap.signum() > 0 ? CapSource.COUNTRY : CapSource.DEFAULT;
        BigDecimal effective = own != null ? own : roleCap != null ? roleCap : countryCap;
        return new EmployeeView(userId, paysId, roleId, roleLabel, own, roleCap, countryCap, effective, source, ref.isPresent());
    }

    @Transactional
    public EmployeeView saveEmployeeCap(Long userId, BigDecimal hours, Long caller) {
        if (userId == null) throw new IllegalArgumentException("Collaborateur obligatoire.");
        Optional<OvertimeCap> existing = capRepo.findByScopeAndProfileUserId(OvertimeCap.EMPLOYEE, userId);
        if (hours == null) {
            existing.ifPresent(capRepo::delete);
        } else {
            checkHours(hours);
            OvertimeCap c = existing.orElseGet(() -> {
                OvertimeCap n = new OvertimeCap();
                n.setScope(OvertimeCap.EMPLOYEE);
                n.setProfileUserId(userId);
                return n;
            });
            c.setCapHours(hours);
            c.setUpdatedBy(caller);
            capRepo.save(c);
        }
        return employeeView(userId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────

    /** The ACTIVE engine parameter set of a country (HR pays id), empty when none. */
    private Map<String, Object> activeParams(Long paysId) {
        return countryRepo.findByPaysIdAndActiveTrue(paysId)
                .flatMap(c -> paramSetRepo.findByCountryIdAndStatus(c.getId(), "ACTIVE"))
                .map(ps -> {
                    try {
                        return objectMapper.readValue(ps.getParameters(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        return Map.<String, Object>of();
                    }
                })
                .orElse(Map.of());
    }

    private static void checkHours(BigDecimal hours) {
        if (hours.signum() < 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0) {
            throw new IllegalArgumentException("Le plafond doit être compris entre 0 et 24 heures par jour.");
        }
    }

    private static void requirePays(Long paysId) {
        if (paysId == null || paysId <= 0) throw new IllegalArgumentException("Pays obligatoire.");
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────────

    public record OvertimeRules(Map<DayOfWeek, BigDecimal> dayPct, BigDecimal defaultPct,
                                BigDecimal dailyCap, CapSource capSource) {
        /** The day's majoration (%), or null when neither the day nor the default is set. */
        public BigDecimal pctFor(DayOfWeek day) {
            BigDecimal own = dayPct.get(day);
            return own != null ? own : defaultPct;
        }
    }

    public record DayRate(String dayOfWeek, BigDecimal ownPct, BigDecimal effectivePct) {}

    public record RoleCap(long roleId, String labelFr, String labelEn, BigDecimal capHours) {}

    public record AdminView(Long paysId, boolean hrRead, List<DayRate> days, BigDecimal defaultPct,
                            BigDecimal countryCap, boolean countryCapFromParams, List<RoleCap> roles) {}

    public record EmployeeView(Long userId, Long paysId, Long roleId, String roleLabel, BigDecimal ownCap,
                               BigDecimal roleCap, BigDecimal countryCap, BigDecimal effectiveCap,
                               CapSource source, boolean hrRead) {}
}
