package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.simulation.dto.SimulationListItemDto;
import com.daf360.payroll.modules.simulation.dto.SimulationPageDto;
import com.daf360.payroll.modules.simulation.dto.SimulationResultDto;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository.IndividualSummary;
import com.daf360.payroll.security.PaysScopeContext;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lecture des simulations individuelles pour la liste `/payroll/simulator` et la fiche
 * `/payroll/simulator/:id` : pagination, filtres et tri côté serveur, comme les listes de la
 * facturation, et contrôle du pays sur la lecture par id (le `PaysIsolationInterceptor` ne
 * voit que les paramètres `paysId` / `pays`, pas un id de simulation).
 */
@Service
@Transactional(readOnly = true)
public class SimulationHistoryService {

    /** Colonne de la liste → propriété JPA triable. Toute autre clé retombe sur la date. */
    private static final Map<String, String> SORT_KEYS = Map.of(
            "simulatedAt",  "simulatedAt",
            "subject",      "candidateLabel",
            "poste",        "poste",
            "contractType", "contractType",
            "mode",         "mode",
            "gross",        "gross",
            "loadedCost",   "loadedCost",
            "costNetRatio", "costNetRatio");

    private static final int MAX_PAGE_SIZE = 100;

    private final SimulationResultRepository resultRepo;
    private final UsersRefRepository usersRefRepo;
    private final UserContextService userContext;
    private final IndividualSimulationService individualService;

    public SimulationHistoryService(SimulationResultRepository resultRepo,
                                    UsersRefRepository usersRefRepo,
                                    UserContextService userContext,
                                    IndividualSimulationService individualService) {
        this.resultRepo = resultRepo;
        this.usersRefRepo = usersRefRepo;
        this.userContext = userContext;
        this.individualService = individualService;
    }

    public SimulationPageDto list(Long paysId, String contractType, String mode, String subject,
                                  String search, boolean partialOnly, int page, int size, String sort) {
        String q = blankToNull(search) == null ? null : "%" + search.trim().toLowerCase() + "%";
        String ct = blankToNull(contractType);
        String md = blankToNull(mode);
        String sj = blankToNull(subject);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        // Bornes des KPI « ce mois-ci » / « mois précédent », en UTC comme `simulated_at`.
        OffsetDateTime monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                .atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime prevMonthStart = monthStart.minusMonths(1);

        Page<SimulationResult> result = resultRepo.searchIndividual(
                paysId, ct, md, sj, q, partialOnly, PageRequest.of(Math.max(0, page), pageSize, toSort(sort)));
        IndividualSummary s = resultRepo.summarizeIndividual(
                paysId, ct, md, sj, q, partialOnly, monthStart, prevMonthStart);

        Map<Long, String> names = namesOf(result.getContent());
        List<SimulationListItemDto> content = result.getContent().stream()
                .map(r -> toItem(r, names))
                .toList();

        long total = s != null ? nz(s.getTotal()) : 0L;
        long employees = s != null ? nz(s.getEmployees()) : 0L;
        return new SimulationPageDto(
                content, result.getTotalElements(), Math.max(1, result.getTotalPages()),
                result.getNumber(), result.getSize(),
                new SimulationPageDto.Summary(
                        total, employees, total - employees,
                        s != null ? nz(s.getMonthCount()) : 0L,
                        s != null ? nz(s.getPrevMonthCount()) : 0L,
                        s != null ? nz(s.getPartialCount()) : 0L,
                        scale(s != null ? s.getAvgLoadedCost() : null, 2),
                        s != null ? s.getMinLoadedCost() : null,
                        s != null ? s.getMaxLoadedCost() : null,
                        scale(s != null ? s.getAvgRatio() : null, 2),
                        scale(s != null ? s.getPrevMonthAvgRatio() : null, 2)));
    }

    /** Fiche complète d'une simulation, refusée hors du périmètre pays de l'utilisateur. */
    public SimulationResultDto getById(Long id) {
        SimulationResult r = findInScope(id);
        return individualService.toDto(r);
    }

    /** Même contrôle pour les exports, qui lisent une simulation par son id. */
    public SimulationResult findInScope(Long id) {
        SimulationResult r = resultRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Simulation introuvable : " + id));
        if (!isSuperAdmin()) {
            PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
            if (r.getPaysId() == null || scope == null || !scope.allows(r.getPaysId())) {
                throw new AccessDeniedException("Simulation hors de votre périmètre pays");
            }
        }
        return r;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SimulationListItemDto toItem(SimulationResult r, Map<Long, String> names) {
        BigDecimal netPaid = r.getGross() == null ? null
                : r.getGross()
                    .subtract(nz(r.getEmployeeCharges()))
                    .subtract(nz(r.getIrppAmount()));
        String subjectLabel = r.getProfileUserId() != null
                ? names.get(r.getProfileUserId())
                : r.getCandidateLabel();
        return new SimulationListItemDto(
                r.getId(), r.getPaysId(), r.getSimulatedAt(), r.getProfileUserId(), subjectLabel,
                r.getPoste(), r.getGrade(), r.getDiscipline(), r.getContractType(), r.getMode(),
                r.getGross(), netPaid, r.getLoadedCost(), r.getCostNetRatio(), r.getLocalCurrency(),
                r.getConvergenceOk(),
                r.getSimulatedBy() != null ? names.get(r.getSimulatedBy()) : null);
    }

    /** Noms des collaborateurs simulés et des auteurs de la page, en une requête. */
    private Map<Long, String> namesOf(List<SimulationResult> rows) {
        Set<Long> ids = new HashSet<>();
        for (SimulationResult r : rows) {
            if (r.getProfileUserId() != null) ids.add(r.getProfileUserId());
            if (r.getSimulatedBy() != null) ids.add(r.getSimulatedBy());
        }
        if (ids.isEmpty()) return Map.of();
        return usersRefRepo.findAllById(ids).stream()
                .filter(u -> u.getFullName() != null)
                .collect(Collectors.toMap(UsersRef::getId, UsersRef::getFullName, (a, b) -> a));
    }

    private static Sort toSort(String sort) {
        Sort byDateDesc = Sort.by(Sort.Direction.DESC, "simulatedAt");
        if (sort == null || sort.isBlank()) return byDateDesc;
        String[] parts = sort.split(",");
        String property = SORT_KEYS.get(parts[0].trim());
        if (property == null) return byDateDesc;
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        // Départage stable par date, pour que la pagination ne mélange pas les égalités.
        return Sort.by(dir, property).and(byDateDesc);
    }

    private static boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "PAYROLL_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** `sum()` sur un périmètre vide rend null, pas 0. */
    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static BigDecimal scale(Double v, int digits) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP);
    }
}
