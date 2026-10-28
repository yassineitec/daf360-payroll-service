package com.daf360.payroll.modules.admin.service;

import com.daf360.payroll.modules.admin.dto.AuditEntryDto;
import com.daf360.payroll.modules.admin.dto.PayrollCountryDto;
import com.daf360.payroll.modules.admin.dto.SavePayrollCountryRequest;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.stream.Collectors;

/**
 * Read-only views behind the payroll administration cards: the engine's per-country set-up
 * (« Pays de paie ») and the change log (« Journal des modifications »).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayrollAdminService {

    public static final String SOURCE_EMPLOYEE_CONFIG = "EMPLOYEE_CONFIG";
    public static final String SOURCE_SALARY_ADVANCE  = "SALARY_ADVANCE";

    /** Hard cap on the log length, whatever the caller asks. */
    static final int MAX_LIMIT = 500;

    private final PayrollCountryRepository               countryRepo;
    private final PaysRefRepository                      paysRefRepo;
    private final UsersRefRepository                     usersRefRepo;
    private final EmployeePayrollConfigHistoryRepository configHistoryRepo;
    private final SalaryAdvanceHistoryRepository         advanceHistoryRepo;
    private final SalaryAdvanceRepository                advanceRepo;
    private final UserContextService                     userContext;

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
        String currency = req.currencyCode() == null ? "" : req.currencyCode().trim().toUpperCase();
        if (!currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Devise invalide : code ISO de 3 lettres attendu (ex. TND)");
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
        c.setCurrencyCode(currency);
        c.setFiscalYearStartMonth(month);
        c.setForexApiSources(forex);
        c.setActive(req.active() != null && req.active());
        c.setEurRate(req.eurRate());
        c.setUsdRate(req.usdRate());
        c.setChfRate(req.chfRate());
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
    public List<AuditEntryDto> audit(Long paysId, int limit, boolean withConfig, boolean withAdvances) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, size);

        List<EmployeePayrollConfigHistory> configs = withConfig
                ? configHistoryRepo.findByPaysIdOrderByChangedAtDesc(paysId, page) : List.of();
        List<SalaryAdvanceHistory> advances = withAdvances
                ? advanceHistoryRepo.findLatestByPaysId(paysId, page) : List.of();

        Map<Long, SalaryAdvance> advanceById = advanceRepo
                .findAllById(advances.stream().map(SalaryAdvanceHistory::getSalaryAdvanceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SalaryAdvance::getId, Function.identity()));

        Set<Long> userIds = new HashSet<>();
        configs.forEach(h -> { userIds.add(h.getChangedBy()); userIds.add(h.getProfileUserId()); });
        advances.forEach(h -> userIds.add(h.getActorUserId()));
        advanceById.values().forEach(a -> userIds.add(a.getProfileUserId()));
        Map<Long, String> names = names(userIds);

        List<AuditEntryDto> out = new ArrayList<>(configs.size() + advances.size());
        for (EmployeePayrollConfigHistory h : configs) {
            out.add(new AuditEntryDto(
                    SOURCE_EMPLOYEE_CONFIG, h.getId(), h.getChangedAt(), h.getPaysId(),
                    h.getChangedBy(), names.get(h.getChangedBy()),
                    h.getProfileUserId(), names.get(h.getProfileUserId()),
                    h.getReason(), null,
                    h.getCurrentGrossSalary() == null
                            ? h.getContractType()
                            : h.getContractType() + " · " + h.getCurrentGrossSalary().toPlainString()));
        }
        for (SalaryAdvanceHistory h : advances) {
            SalaryAdvance a = advanceById.get(h.getSalaryAdvanceId());
            Long employee = a == null ? null : a.getProfileUserId();
            out.add(new AuditEntryDto(
                    SOURCE_SALARY_ADVANCE, h.getId(), h.getCreatedAt(), paysId,
                    h.getActorUserId(), names.get(h.getActorUserId()),
                    h.getSalaryAdvanceId(), names.get(employee),
                    h.getToStatus(), h.getFromStatus(), h.getNotes()));
        }
        out.sort(Comparator.comparing(AuditEntryDto::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > size ? out.subList(0, size) : out;
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
