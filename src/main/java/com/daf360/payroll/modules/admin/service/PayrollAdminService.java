package com.daf360.payroll.modules.admin.service;

import com.daf360.payroll.modules.admin.dto.AuditEntryDto;
import com.daf360.payroll.modules.admin.dto.PayrollCountryDto;
import com.daf360.payroll.modules.admin.dto.SavePayrollCountryRequest;
import com.daf360.payroll.modules.admin.entity.AuditTrail;
import com.daf360.payroll.modules.admin.repository.AuditTrailRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfigHistory;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigHistoryRepository;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.ref.entity.PaysRef;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.PaysRefRepository;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvance;
import com.daf360.payroll.modules.salaryadvance.entity.SalaryAdvanceHistory;
import com.daf360.payroll.modules.salaryadvance.repository.SalaryAdvanceHistoryRepository;
import com.daf360.payroll.modules.salaryadvance.repository.SalaryAdvanceRepository;
import com.daf360.payroll.security.PaysScopeContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Read-only views behind the payroll administration cards: the engine's per-country set-up
 * (« Pays de paie ») and the change log (« Journal des modifications »).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayrollAdminService {

    private static final Logger log = LoggerFactory.getLogger(PayrollAdminService.class);

    public static final String SOURCE_EMPLOYEE_CONFIG = "EMPLOYEE_CONFIG";
    public static final String SOURCE_SALARY_ADVANCE  = "SALARY_ADVANCE";
    public static final String SOURCE_GENERIC         = "GENERIC";

    /** Hard cap on the log length, whatever the caller asks. */
    static final int MAX_LIMIT = 500;

    private final PayrollCountryRepository               countryRepo;
    private final PaysRefRepository                      paysRefRepo;
    private final UsersRefRepository                     usersRefRepo;
    private final EmployeePayrollConfigHistoryRepository configHistoryRepo;
    private final SalaryAdvanceHistoryRepository         advanceHistoryRepo;
    private final SalaryAdvanceRepository                advanceRepo;
    private final AuditTrailRepository                   auditTrailRepo;
    private final UserContextService                     userContext;
    private final PlatformTransactionManager              txManager;

    /**
     * Every payroll country in the caller's scope — same scope rule as the advance policies
     * list: the endpoint takes no {@code paysId}, so {@code PaysIsolationInterceptor} does not
     * filter it and the service does.
     */
    public List<PayrollCountryDto> listCountries() {
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        Map<Long, PaysRef> pays = paysRefRepo.findAll().stream()
                .collect(Collectors.toMap(PaysRef::getId, Function.identity(), (a, b) -> a));
        return countryRepo.findAll().stream()
                .filter(c -> scope == null || scope.all() || scope.allows(c.getPaysId()))
                .map(c -> toDto(c, pays.get(c.getPaysId())))
                .sorted(Comparator.comparing(PayrollCountryDto::paysLabel, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /** Configure un nouveau pays dans le moteur. Refusé si le pays l'est déjà (409) ou s'il
     *  est hors du périmètre de l'appelant (403). */
    @Transactional
    public PayrollCountryDto createCountry(SavePayrollCountryRequest req) {
        if (req.paysId() == null) throw new IllegalArgumentException("Pays obligatoire");
        assertInScope(req.paysId());
        PaysRef pays = paysRefRepo.findById(req.paysId())
                .orElseThrow(() -> new IllegalArgumentException("Pays inconnu du référentiel : " + req.paysId()));
        if (countryRepo.findByPaysId(req.paysId()).isPresent())
            throw new IllegalStateException("Ce pays est déjà configuré dans le moteur de paie");
        PayrollCountry c = new PayrollCountry();
        c.setPaysId(req.paysId());
        c.setCurrencyCode(validateCurrency(req.currencyCode()));
        apply(c, req);
        return toDto(countryRepo.save(c), pays);
    }

    /** Modifie la configuration d'un pays (devise, début d'exercice, sources de change,
     *  activation). Le pays lui-même ne change pas. */
    @Transactional
    public PayrollCountryDto updateCountry(Long id, SavePayrollCountryRequest req) {
        PayrollCountry c = countryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Pays de paie introuvable : " + id));
        assertInScope(c.getPaysId());
        apply(c, req);
        return toDto(countryRepo.save(c), paysRefRepo.findById(c.getPaysId()).orElse(null));
    }

    private void apply(PayrollCountry c, SavePayrollCountryRequest req) {
        int month = req.fiscalYearStartMonth() == null ? 1 : req.fiscalYearStartMonth();
        if (month < 1 || month > 12)
            throw new IllegalArgumentException("Premier mois de l'exercice invalide : 1 à 12");
        if (req.eurRate() != null && req.eurRate().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Taux EUR invalide : doit être strictement positif");
        if (req.usdRate() != null && req.usdRate().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Taux USD invalide : doit être strictement positif");
        if (req.chfRate() != null && req.chfRate().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Taux CHF invalide : doit être strictement positif");
        String forex = req.forexApiSources() == null || req.forexApiSources().isBlank() ? null : req.forexApiSources().trim();
        if (forex != null) {
            try {
                JsonNode node = JSON.readTree(forex);
                if (!node.isArray() && !node.isObject())
                    throw new IllegalArgumentException("Sources de change : tableau ou objet JSON attendu");
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Sources de change : JSON invalide");
            }
        }
        c.setFiscalYearStartMonth(month);
        c.setForexApiSources(forex);
        c.setActive(req.active() != null && req.active());
        c.setEurRate(req.eurRate());
        c.setUsdRate(req.usdRate());
        c.setChfRate(req.chfRate());
    }

    /** Only called from createCountry() -- a brand-new row needs an initial currency from
     *  somewhere, and for a country with no facturation entity to sync from (not one of the 13
     *  ARX entities) this manual value is the only source there'll ever be. updateCountry()
     *  deliberately never calls this: currency_code is now managed centrally in facturation's
     *  admin screen and kept in sync by ProfileSyncService.syncEntityCurrencies(), so an edit
     *  here would just get silently overwritten at the next 15-minute sync anyway. */
    private String validateCurrency(String raw) {
        String currency = raw == null ? "" : raw.trim().toUpperCase();
        if (!currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Devise invalide : code ISO de 3 lettres attendu (ex. TND)");
        return currency;
    }

    /** Même règle de périmètre que les règles d'avances : le corps de la requête échappe à
     *  `PaysIsolationInterceptor` (qui ne lit que les paramètres d'URL), le service vérifie. */
    private void assertInScope(Long paysId) {
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        if (scope != null && !scope.all() && !scope.allows(paysId))
            throw new AccessDeniedException("Ce pays est hors de votre périmètre");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The country's latest changes, newest first, merged from the sources the caller may read.
     * {@code paysId} is a request parameter, so {@code PaysIsolationInterceptor} has already
     * refused a country outside the caller's scope.
     */
    public List<AuditEntryDto> audit(Long paysId, int limit, boolean withConfig, boolean withAdvances, boolean withGeneric) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, size);

        List<EmployeePayrollConfigHistory> configs = withConfig
                ? safeList(SOURCE_EMPLOYEE_CONFIG, () -> configHistoryRepo.findByPaysIdOrderByChangedAtDesc(paysId, page))
                : List.of();
        List<SalaryAdvanceHistory> advances = withAdvances
                ? safeList(SOURCE_SALARY_ADVANCE, () -> advanceHistoryRepo.findLatestByPaysId(paysId, page))
                : List.of();
        List<AuditTrail> generic = withGeneric
                ? safeList(SOURCE_GENERIC, () -> auditTrailRepo.findByPaysId(paysId, page))
                : List.of();

        Map<Long, SalaryAdvance> advanceById = advanceRepo
                .findAllById(advances.stream().map(SalaryAdvanceHistory::getSalaryAdvanceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SalaryAdvance::getId, Function.identity()));

        Set<Long> userIds = new HashSet<>();
        configs.forEach(h -> { userIds.add(h.getChangedBy()); userIds.add(h.getProfileUserId()); });
        advances.forEach(h -> userIds.add(h.getActorUserId()));
        advanceById.values().forEach(a -> userIds.add(a.getProfileUserId()));
        // GENERIC's changed_by is the raw SESSION_CONTEXT('app_user') value -- this platform's
        // JWT principal is the caller's users_ref id, not a username, so it needs the same
        // resolution as every other source's actor instead of reaching the screen as a bare id.
        generic.forEach(g -> { Long id = parseUserId(g.getChangedBy()); if (id != null) userIds.add(id); });
        Map<Long, String> names = names(userIds);

        List<AuditEntryDto> out = new ArrayList<>(configs.size() + advances.size() + generic.size());
        for (EmployeePayrollConfigHistory h : configs) {
            out.add(new AuditEntryDto(
                    SOURCE_EMPLOYEE_CONFIG, h.getId(), h.getChangedAt(), h.getPaysId(),
                    h.getChangedBy(), names.get(h.getChangedBy()),
                    h.getProfileUserId(), names.get(h.getProfileUserId()),
                    h.getReason(), null,
                    h.getCurrentGrossSalary() == null
                            ? h.getContractType()
                            : h.getContractType() + " · " + h.getCurrentGrossSalary().toPlainString(),
                    null, null));
        }
        for (SalaryAdvanceHistory h : advances) {
            SalaryAdvance a = advanceById.get(h.getSalaryAdvanceId());
            Long employee = a == null ? null : a.getProfileUserId();
            out.add(new AuditEntryDto(
                    SOURCE_SALARY_ADVANCE, h.getId(), h.getCreatedAt(), paysId,
                    h.getActorUserId(), names.get(h.getActorUserId()),
                    h.getSalaryAdvanceId(), names.get(employee),
                    h.getToStatus(), h.getFromStatus(), h.getNotes(),
                    null, null));
        }
        for (AuditTrail g : generic) {
            Long actorId = parseUserId(g.getChangedBy());
            // Unresolved (unknown id, or changed_by was never a numeric id to begin with --
            // an ad-hoc script, a test fixture) falls back to the raw value, same as before
            // this method started resolving it: never go from "something" to blank.
            String actorName = actorId != null ? names.getOrDefault(actorId, g.getChangedBy()) : g.getChangedBy();
            out.add(new AuditEntryDto(
                    SOURCE_GENERIC, g.getId(), g.getChangedAt(), paysId,
                    actorId, actorName,
                    null, g.getTableName() + " " + g.getPkJson(),
                    actionLabel(g.getAction()), null, g.getTableName(),
                    g.getOldValue(), g.getNewValue()));
        }
        out.sort(Comparator.comparing(AuditEntryDto::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > size ? out.subList(0, size) : out;
    }

    /**
     * Runs one source's query in isolation: a source that is unavailable (e.g. a table not yet
     * provisioned in this environment) logs a warning and drops out of the merged log instead of
     * failing the whole {@link #audit} call for every other source too.
     *
     * The query runs in its own {@code REQUIRES_NEW} transaction rather than joining
     * {@code audit()}'s read-only one: a {@link DataAccessException} crossing a transactional
     * proxy boundary marks the transaction it belongs to rollback-only, even once this method
     * catches it. Without this isolation, one failing source still dooms the shared transaction
     * and {@code audit()} fails at commit time with {@code UnexpectedRollbackException} — the
     * very failure this method exists to avoid.
     */
    private <T> List<T> safeList(String source, Supplier<List<T>> query) {
        TransactionTemplate isolated = new TransactionTemplate(txManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setReadOnly(true);
        try {
            return isolated.execute(status -> query.get());
        } catch (DataAccessException e) {
            log.warn("Audit source {} unavailable, omitting from the merged log: {}", source, e.getMessage());
            return List.of();
        }
    }

    private static String actionLabel(String code) {
        return switch (code) {
            case "I" -> "INSERT";
            case "U" -> "UPDATE";
            case "D" -> "DELETE";
            default -> code;
        };
    }

    /** {@code changed_by} is free-text as far as the trigger is concerned -- null for a
     *  scheduled job, this platform's numeric user id for a real request. Anything else
     *  (never expected, but not worth failing the whole audit call over) is left unresolved. */
    private static Long parseUserId(String raw) {
        if (raw == null) return null;
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<Long, String> names(Collection<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) return Map.of();
        return usersRefRepo.findAllById(wanted).stream()
                .collect(Collectors.toMap(UsersRef::getId, UsersRef::getFullName, (a, b) -> a));
    }

    private static PayrollCountryDto toDto(PayrollCountry c, PaysRef p) {
        return new PayrollCountryDto(
                c.getId(), c.getPaysId(),
                p == null ? null : p.getFrenchLabel(),
                p == null ? null : p.getIsoCode(),
                c.getCurrencyCode(), c.getFiscalYearStartMonth(), c.getForexApiSources(),
                c.isActive(), c.getCreatedAt(),
                c.getEurRate(), c.getUsdRate(), c.getChfRate());
    }
}
