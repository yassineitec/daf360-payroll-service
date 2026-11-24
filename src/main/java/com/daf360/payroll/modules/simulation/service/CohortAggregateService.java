package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient.HrEmployeeDto;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse;
import com.daf360.payroll.modules.simulation.dto.CohortAggregateResponse.CohortMessage;
import com.daf360.payroll.modules.simulation.dto.CohortFilterRequest;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfRequest;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfResponse;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfRow;
import com.daf360.payroll.modules.simulation.service.CohortEmployeeCalculator.Change;
import com.daf360.payroll.modules.simulation.service.CohortEmployeeCalculator.ChangeType;
import com.daf360.payroll.modules.simulation.service.CohortEmployeeCalculator.EngineContext;
import com.daf360.payroll.modules.simulation.service.CohortEmployeeCalculator.NonPositiveSalaryException;
import com.daf360.payroll.modules.simulation.service.CohortEmployeeCalculator.SalaryBase;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cohort aggregate + salary what-if (hardened, A1–A6 / B1).
 *
 * <p>Fetches the country's active workforce from HR (503 if HR cannot be read), applies the
 * filters, runs the engine for every employee with a salary — current figures, then the change
 * applied to the NET or the GROSS — and sums only employees whose computation converged.
 * Who sees what is decided by {@link CohortAccess}: an AGGREGATE caller gets totals only, and
 * nothing but counts below {@link CohortSettings#minGroupSize()} employees.
 *
 * <p>Nothing is persisted (DEC13); every run writes one access-log line with the caller, the
 * scope and the counts — never an amount, a name or an employee id.
 */
@Slf4j
@Service
public class CohortAggregateService {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private final HrEmployeeClient hrClient;
    private final ParameterSetService paramSetService;
    private final CohortEmployeeCalculator calculator;
    private final CohortFxResolver fxResolver;
    private final EmployeePayrollConfigRepository configRepo;
    private final CohortSettings settings;

    public CohortAggregateService(HrEmployeeClient hrClient, ParameterSetService paramSetService,
                                  CohortEmployeeCalculator calculator, CohortFxResolver fxResolver,
                                  EmployeePayrollConfigRepository configRepo, CohortSettings settings) {
        this.hrClient = hrClient;
        this.paramSetService = paramSetService;
        this.calculator = calculator;
        this.fxResolver = fxResolver;
        this.configRepo = configRepo;
        this.settings = settings;
    }

    /** Who is asking: {@code individual} = PAYROLL_VIEW_INDIVIDUAL (or super-admin). */
    public record CohortAccess(boolean individual, Long actorId, String permissions) {}

    // ── Group ────────────────────────────────────────────────────────────────────────────

    public CohortAggregateResponse aggregate(CohortFilterRequest req, CohortAccess access) {
        if (req.entite() != null && !req.entite().isBlank()) {
            throw new IllegalArgumentException(
                    "Le filtre « entite » n'est pas pris en charge et a été retiré : laissez-le vide.");
        }
        Change change = changeOf(req.modifierType(), req.modifierValue(), req.salaryBase());
        boolean wantRows = Boolean.TRUE.equals(req.includeEmployees());
        if (wantRows && !access.individual()) {
            throw new AccessDeniedException(
                    "La vue par employé est réservée aux détenteurs du droit PAYROLL_VIEW_INDIVIDUAL.");
        }

        EngineContext ctx = engineContext(req.paysId());
        ParameterSet ps = ctx.ps();
        CohortFxResolver.Resolution fx = fxResolver.resolve(req.paysId());
        List<HrEmployeeDto> workforce = hrClient.fetchActiveEmployeesOrThrow(req.paysId());

        List<HrEmployeeDto> matched = workforce.stream()
                .filter(e -> matches(req.grade(), e.grade()))
                .filter(e -> matches(req.discipline(), e.discipline()))
                .filter(e -> matches(req.contractType(), e.contractType()))
                .toList();
        List<HrEmployeeDto> withSalary = matched.stream().filter(CohortEmployeeCalculator::hasSalary).toList();
        int excludedNoSalary = matched.size() - withSalary.size();
        Map<Long, EmployeePayrollConfig> configs = configsOf(withSalary, req.paysId());

        List<EmployeeWhatIfRow> rows = new ArrayList<>();
        int failed = 0;
        for (HrEmployeeDto e : withSalary) {
            try {
                rows.add(calculator.compute(ctx, e, configs.get(e.userId()), change));
            } catch (NonPositiveSalaryException ex) {
                throw ex;                       // DEC3: the whole request is rejected (400)
            } catch (RuntimeException ex) {
                failed++;                       // counted, never summed; no id in the log
            }
        }
        if (failed > 0) log.warn("[cohort] {} employee computation(s) failed for paysId={}", failed, req.paysId());

        List<EmployeeWhatIfRow> included = rows.stream().filter(r -> !r.nonConverged()).toList();
        int nonConverged = rows.size() - included.size();
        int headcount = included.size();
        int benefitsAssumed = (int) rows.stream().filter(EmployeeWhatIfRow::benefitsAssumed).count();
        int grossFromConfig = (int) rows.stream().filter(r -> "CONFIG".equals(r.grossSource())).count();
        int benefitsNoneSelected = (int) rows.stream().filter(EmployeeWhatIfRow::benefitsNoneSelected).count();
        BigDecimal coverage = matched.isEmpty() ? null
                : BigDecimal.valueOf(headcount).divide(BigDecimal.valueOf(matched.size()), 4, RoundingMode.HALF_UP);
        boolean lowCoverage = coverage != null && coverage.compareTo(settings.minCoverage()) < 0;
        boolean suppressed = !access.individual() && headcount > 0 && headcount < settings.minGroupSize();
        boolean showAmounts = headcount > 0 && !suppressed;

        List<CohortMessage> messages = new ArrayList<>();
        if (matched.isEmpty()) {
            messages.add(CohortMessage.info("EMPTY", "Aucun employé actif ne correspond à ces filtres."));
        }
        if (excludedNoSalary > 0) {
            messages.add(CohortMessage.warn("EXCLUDED_NO_SALARY", excludedNoSalary
                    + " employé(s) sans salaire net dans le dossier RH : non simulé(s), exclu(s) des totaux."));
        }
        if (failed > 0) {
            messages.add(CohortMessage.warn("FAILED", failed + " calcul(s) en erreur : exclu(s) des totaux."));
        }
        if (nonConverged > 0) {
            messages.add(headcount == 0
                    ? CohortMessage.error("ALL_NON_CONVERGED", "Aucun des " + nonConverged
                        + " calculs n'a convergé avec le jeu de paramètres actif : aucun total n'est affiché. "
                        + "Vérifiez le jeu de paramètres du pays (avantages, taux) avant de relancer.")
                    : CohortMessage.warn("NON_CONVERGED", nonConverged
                        + " calcul(s) n'ont pas convergé : exclu(s) des totaux."));
        }
        if (benefitsAssumed > 0) {
            messages.add(CohortMessage.warn("BENEFITS_ASSUMED", benefitsAssumed
                    + " employé(s) sans configuration de paie : tous les avantages du jeu de paramètres leur sont appliqués."));
        }
        if (benefitsNoneSelected > 0) {
            messages.add(CohortMessage.warn("BENEFITS_NONE_SELECTED", benefitsNoneSelected
                    + " employé(s) ont une configuration de paie sans aucun avantage sélectionné : ils sont calculés"
                    + " sans avantage, ce qui augmente le brut nécessaire pour un net donné."));
        }
        if (lowCoverage) {
            messages.add(CohortMessage.warn("LOW_COVERAGE", headcount + " employé(s) sur " + matched.size()
                    + " sont inclus dans les totaux : les chiffres ne couvrent qu'une partie du groupe."));
        }
        if (suppressed) {
            messages.add(CohortMessage.warn("SUPPRESSED", "Groupe trop petit (" + headcount + " < "
                    + settings.minGroupSize() + ") : les montants ne sont pas affichés pour préserver la confidentialité."));
        }
        messages.addAll(fx.messages());

        Function<Function<EmployeeWhatIfRow, BigDecimal>, BigDecimal> sum = f -> included.stream()
                .map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal current = showAmounts ? sum.apply(r -> r.current().loadedCost()) : null;
        BigDecimal projected = showAmounts ? sum.apply(r -> r.projected().loadedCost()) : null;
        BigDecimal delta = showAmounts ? projected.subtract(current) : null;
        BigDecimal n = BigDecimal.valueOf(Math.max(headcount, 1));
        Function<Function<EmployeeWhatIfRow, BigDecimal>, BigDecimal> avg = f ->
                showAmounts ? sum.apply(f).divide(n, 4, RoundingMode.HALF_UP) : null;

        CohortAggregateResponse res = new CohortAggregateResponse(
                headcount,
                current, projected, delta,
                showAmounts ? delta.multiply(TWELVE) : null,
                showAmounts ? fx.toEur(current) : null,
                showAmounts ? fx.toEur(projected) : null,
                showAmounts ? fx.toChf(current) : null,
                showAmounts ? fx.toChf(projected) : null,
                fx.fx().currency(),
                req.modifierType() != null && !req.modifierType().isBlank()
                        ? req.modifierType().trim().toUpperCase() : "PCT",
                req.modifierValue() != null ? req.modifierValue() : BigDecimal.ZERO,
                req,
                access.individual() ? "INDIVIDUAL" : "AGGREGATE",
                suppressed, settings.minGroupSize(),
                matched.size(), excludedNoSalary, failed, nonConverged, benefitsAssumed, grossFromConfig,
                benefitsNoneSelected, coverage, settings.minCoverage(),
                ps.getId(), ps.getVersion(), ps.getFiscalYear(),
                OffsetDateTime.now(), fx.summary(), fx.fx(),
                change.type().name(), change.base().name(), ctx.workingDays(),
                avg.apply(r -> r.current().netInHand()), avg.apply(r -> r.projected().netInHand()),
                avg.apply(r -> r.current().gross()), avg.apply(r -> r.projected().gross()),
                showAmounts ? CohortEmployeeCalculator.pct(delta, current) : null,
                messages,
                wantRows ? rows : null);

        accessLog("GROUP", req.paysId(), access, change,
                "grade=" + label(req.grade()) + ",discipline=" + label(req.discipline())
                        + ",contract=" + label(req.contractType()) + ",rows=" + wantRows,
                "matched=" + matched.size() + ",included=" + headcount + ",excludedNoSalary=" + excludedNoSalary
                        + ",failed=" + failed + ",nonConverged=" + nonConverged + ",suppressed=" + suppressed);
        return res;
    }

    // ── One employee (B1, DEC12) ────────────────────────────────────────────────────────

    public EmployeeWhatIfResponse whatIfEmployee(EmployeeWhatIfRequest req, CohortAccess access) {
        if (!access.individual()) {
            throw new AccessDeniedException(
                    "La simulation d'un employé est réservée aux détenteurs du droit PAYROLL_VIEW_INDIVIDUAL.");
        }
        Change change = changeOf(req.modifierType(), req.modifierValue(), req.salaryBase());
        EngineContext ctx = engineContext(req.paysId());
        ParameterSet ps = ctx.ps();
        CohortFxResolver.Resolution fx = fxResolver.resolve(req.paysId());

        // DEC12: the employee must be in the caller's country — i.e. in that country's active
        // workforce, the country itself being scope-checked (PaysScopeBodyAdvice).
        HrEmployeeDto e = hrClient.fetchActiveEmployeesOrThrow(req.paysId()).stream()
                .filter(x -> Objects.equals(x.userId(), req.profileUserId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "Employé introuvable parmi les employés actifs de ce pays."));
        if (!CohortEmployeeCalculator.hasSalary(e)) {
            throw new IllegalArgumentException(
                    "Cet employé n'a pas de salaire net dans son dossier RH : simulation impossible.");
        }
        EmployeePayrollConfig cfg = configRepo.findByProfileUserId(e.userId())
                .filter(c -> c.getPaysId() == null || c.getPaysId().equals(req.paysId()))
                .orElse(null);
        EmployeeWhatIfRow row = calculator.compute(ctx, e, cfg, change);
        CohortEmployeeCalculator.BenefitChoice bc = calculator.benefitsFor(ctx, cfg);

        List<CohortMessage> messages = new ArrayList<>(fx.messages());
        if (row.nonConverged()) {
            messages.add(0, CohortMessage.error("NON_CONVERGED",
                    "Le calcul n'a pas convergé avec le jeu de paramètres actif : résultat approximatif."));
        }
        if (row.benefitsAssumed()) {
            messages.add(CohortMessage.warn("BENEFITS_ASSUMED",
                    "Aucune configuration de paie pour cet employé : tous les avantages du jeu de paramètres sont appliqués."));
        }
        if (row.benefitsNoneSelected()) {
            messages.add(CohortMessage.warn("BENEFITS_NONE_SELECTED",
                    "La configuration de paie de cet employé ne sélectionne aucun avantage : il est calculé sans avantage,"
                            + " ce qui augmente le brut nécessaire pour un net donné."));
        }

        accessLog("EMPLOYEE", req.paysId(), access, change, "employee=1",
                "nonConverged=" + row.nonConverged());
        return new EmployeeWhatIfResponse(
                row, fx.fx().currency(), change.type().name(), change.value(), change.base().name(),
                ps.getId(), ps.getVersion(), ps.getFiscalYear(), ctx.workingDays(), OffsetDateTime.now(),
                fx.summary(), fx.fx(),
                fx.toEur(row.current().loadedCost()), fx.toEur(row.projected().loadedCost()),
                fx.toChf(row.current().loadedCost()), fx.toChf(row.projected().loadedCost()),
                bc.benefits().stream().map(b -> b.getBenefitCode()).toList(),
                messages);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    /** Parses and bounds-checks the change (A4 / DEC3). */
    static Change changeOf(String type, BigDecimal value, String base) {
        Change c = new Change(ChangeType.parse(type), value != null ? value : BigDecimal.ZERO, SalaryBase.parse(base));
        c.validate();
        return c;
    }

    private EngineContext engineContext(Long paysId) {
        ParameterSet ps = paramSetService.loadActiveEntity(paysId);
        return new EngineContext(ps, paramSetService.loadRates(ps.getId()), paramSetService.loadBenefits(ps.getId()),
                paramSetService.loadRubriques(ps.getId()), settings.workingDays(), settings.grossConsistencyTolerance());
    }

    private Map<Long, EmployeePayrollConfig> configsOf(List<HrEmployeeDto> employees, Long paysId) {
        List<Long> ids = employees.stream().map(HrEmployeeDto::userId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        return configRepo.findByProfileUserIdIn(ids).stream()
                .filter(c -> c.getPaysId() == null || c.getPaysId().equals(paysId))
                .collect(Collectors.toMap(EmployeePayrollConfig::getProfileUserId, c -> c, (a, b) -> a));
    }

    private static boolean matches(String filter, String value) {
        return filter == null || filter.isBlank() || filter.trim().equalsIgnoreCase(value == null ? null : value.trim());
    }

    private static String label(String filter) {
        return filter == null || filter.isBlank() ? "*" : filter.trim();
    }

    /** DEC13 — who ran what, on which scope, with which counts. No amount, no name, no employee id. */
    private static void accessLog(String scope, Long paysId, CohortAccess access, Change change,
                                  String filters, String counts) {
        log.info("[cohort-access] actor={} perms={} pays={} scope={} change={}/{} filters={} counts={}",
                access.actorId(), access.permissions(), paysId, scope, change.type(), change.base(), filters, counts);
    }
}
