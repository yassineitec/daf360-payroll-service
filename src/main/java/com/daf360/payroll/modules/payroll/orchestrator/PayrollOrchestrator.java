package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.dto.RunPayrollResponse;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollRubriqueDefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Main orchestrator that ties together context loading, sequence validation,
 * per-rubrique dispatch, aggregation, persistence, and forex snapshotting.
 *
 * <h3>Charge variable bridge</h3>
 * When an active {@code ParameterSet} exists for the requested {@code paysId},
 * the orchestrator injects TopologicalEvaluator charge results ({@code CNSS_EE},
 * {@code CHARGES_EE}, {@code CHARGES_ER}, etc.) into the execution context at
 * the strate-1 → strate-2 boundary, where {@code BRUT} is first fully known.
 * This makes the new formula-mode charge variables available to any rubrique
 * running in strate 2 or later without affecting the existing SpEL formulas.
 */
@Service
public class PayrollOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PayrollOrchestrator.class);

    private final ContextLoader contextLoader;
    private final SequenceValidator sequenceValidator;
    private final CalculationDispatcher dispatcher;
    private final SystemVariableAggregator aggregator;
    private final ResultPersister persister;
    private final ForexSnapshotService forexService;
    private final PayrollRubriqueDefRepository rubriqueRepo;
    private final PayrollCountryRepository countryRepo;
    private final TopologicalEvaluator topologicalEvaluator;
    private final ParameterSetService parameterSetService;
    private final EmployeePayrollConfigRepository employeeConfigRepo;

    public PayrollOrchestrator(ContextLoader contextLoader,
                                SequenceValidator sequenceValidator,
                                CalculationDispatcher dispatcher,
                                SystemVariableAggregator aggregator,
                                ResultPersister persister,
                                ForexSnapshotService forexService,
                                PayrollRubriqueDefRepository rubriqueRepo,
                                PayrollCountryRepository countryRepo,
                                TopologicalEvaluator topologicalEvaluator,
                                ParameterSetService parameterSetService,
                                EmployeePayrollConfigRepository employeeConfigRepo) {
        this.contextLoader = contextLoader;
        this.sequenceValidator = sequenceValidator;
        this.dispatcher = dispatcher;
        this.aggregator = aggregator;
        this.persister = persister;
        this.forexService = forexService;
        this.rubriqueRepo = rubriqueRepo;
        this.countryRepo = countryRepo;
        this.topologicalEvaluator = topologicalEvaluator;
        this.parameterSetService = parameterSetService;
        this.employeeConfigRepo = employeeConfigRepo;
    }

    @Transactional
    public RunPayrollResponse run(RunPayrollRequest request) {
        // 1. Load context (country, param set, system vars)
        ExecutionContext ctx = contextLoader.load(request);
        Long paramSetId = contextLoader.resolveParamSetId(request.getPaysId());

        // 2. Load active rubriques for country, sorted by displayOrder
        PayrollCountry country = countryRepo.findByPaysIdAndActiveTrue(request.getPaysId())
            .orElseThrow(() -> new IllegalStateException(
                "No active country for paysId=" + request.getPaysId()));
        List<PayrollRubriqueDef> rubriques = rubriqueRepo
            .findByCountryIdAndActiveTrue(country.getId())
            .stream()
            .sorted(Comparator.comparingInt(PayrollRubriqueDef::getDisplayOrder))
            .collect(Collectors.toList());

        // 3. Validate sequence (no forward references)
        sequenceValidator.validate(rubriques);

        // 3b. Salaire de base DU COLLABORATEUR (« Salaires des collaborateurs ») à la place du
        //     montant unique du jeu de paramètres — voir applyEmployeeBaseSalary.
        applyEmployeeBaseSalary(ctx, rubriques, request.getEmployeeId());

        // 4. Execute each rubrique in order
        List<RubriqueResultItem> results = new ArrayList<>();
        boolean convergenceOk = true;
        int totalIterations = 0;

        String contractType = request.getContractTypeCode() != null ? request.getContractTypeCode() : "CDI";
        int joursTravailes  = request.getJoursOuvresMois() != null ? request.getJoursOuvresMois().intValue() : 22;
        boolean chargesInjected = false;

        for (PayrollRubriqueDef rubrique : rubriques) {
            // Inject formula-engine charge variables (CNSS_EE, CHARGES_EE, …) once
            // BRUT is fully known — i.e. at the first rubrique belonging to strate 2+.
            if (!chargesInjected && rubrique.getStrate() > 1 && ctx.hasVariable("BRUT")) {
                injectChargeVariables(ctx, request.getPaysId(), contractType, joursTravailes);
                chargesInjected = true;
            }

            BigDecimal amount = dispatcher.dispatch(rubrique, ctx);
            ctx.putVariable(rubrique.getCode(), amount);

            RubriqueResultItem item = RubriqueResultItem.builder()
                .rubriqueCode(rubrique.getCode())
                .labelFr(rubrique.getLabelFr())
                .labelEn(rubrique.getLabelEn())
                .nature(rubrique.getNature())
                .strate(rubrique.getStrate())
                .assiette(rubrique.getAssietteCode() != null
                    ? ctx.getVariable(rubrique.getAssietteCode())
                    : BigDecimal.ZERO)
                .amount(amount)
                .modeCalcul(rubrique.getModeCalcul())
                .prorataApplied(rubrique.isProrataApplicable())
                .build();
            results.add(item);

            // After each rubrique, refresh strate aggregates in context
            aggregator.aggregate(results, ctx);
        }

        // 4b. Pays dont toutes les rubriques sont en strate 1 : le crochet de la boucle n'a pas
        //     injecté les charges — on le fait AVANT d'enregistrer, pour que le résultat porte
        //     les vraies charges patronales (CHARGES_ER, lues par ResultPersister).
        if (!chargesInjected && ctx.hasVariable("BRUT")) {
            injectChargeVariables(ctx, request.getPaysId(), contractType, joursTravailes);
            chargesInjected = true;
        }

        // 5. Persist result — all fields set before the single INSERT; no UPDATE follows
        PayrollResult saved = persister.persist(
            ctx, results, paramSetId,
            request.getTriggeredBy(),
            convergenceOk,
            totalIterations > 0 ? totalIterations : null
        );

        // 7. Persist forex snapshots — fire-and-forget; don't fail the run if forex fails
        try {
            forexService.fetchAndPersist(saved.getId(), request.getPaysId());
        } catch (Exception ignored) {
            // forex snapshot failure must never abort a payroll run
        }

        // 8. Build response (charges déjà injectées à l'étape 4b si besoin).
        return RunPayrollResponse.builder()
            .resultId(saved.getId())
            .employeeId(saved.getEmployeeId())
            .periodYear(saved.getPeriodYear())
            .periodMonth(saved.getPeriodMonth())
            .parameterSetId(saved.getParameterSetId())
            .strate1(saved.getStrate1())
            .strate2(saved.getStrate2())
            .strate3(saved.getStrate3())
            .strate4(saved.getStrate4())
            .strate5(saved.getStrate5())
            .aggregateGross(saved.getAggregateGross())
            .aggregateEmployerCharges(saved.getAggregateEmployerCharges())
            .aggregateNet(saved.getAggregateNet())
            .aggregateIrpp(saved.getAggregateIrpp())
            .loadedCost(saved.getLoadedCost())
            .convergenceOk(convergenceOk)
            .iterationsUsed(totalIterations > 0 ? totalIterations : null)
            .calculatedAt(saved.getCalculatedAt())
            .rubriqueDetails(results)
            .build();
    }

    // -----------------------------------------------------------------------
    //  Charge variable bridge — TopologicalEvaluator → ExecutionContext
    // -----------------------------------------------------------------------

    /**
     * Evaluates social charges for the active {@code ParameterSet} of the given
     * {@code paysId} using the current {@code BRUT} value from the context, then
     * injects all resolved variables ({@code CNSS_EE}, {@code CNSS_ER},
     * {@code CHARGES_EE}, {@code CHARGES_ER}, etc.) into {@code ctx} so that
     * any subsequent rubrique formula may reference them.
     *
     * <p>This is a best-effort operation: if no active parameter set exists or
     * the evaluation fails, the batch run continues without the new-model
     * charge variables (existing SpEL formulas are unaffected).
     */
    private void injectChargeVariables(ExecutionContext ctx, Long paysId,
                                        String contractType, int joursTravailes) {
        try {
            List<SocialChargeRate> rates = parameterSetService.loadRates(
                    parameterSetService.loadActiveEntity(paysId).getId());

            if (rates.isEmpty()) return;

            BigDecimal brut = ctx.getVariable("BRUT");
            if (brut.compareTo(BigDecimal.ZERO) <= 0) return;

            TopologicalEvaluator.EvaluationResult chargeResult =
                    topologicalEvaluator.evaluate(brut, rates, contractType, List.of(), joursTravailes);

            // Inject all resolved variables (charge codes + CHARGES_EE/ER aggregates)
            chargeResult.context().forEach(ctx::putVariable);

            log.debug("injectChargeVariables: BRUT={}, CHARGES_EE={}, CHARGES_ER={}, paysId={}",
                    brut, chargeResult.totalChargesEE(), chargeResult.totalChargesER(), paysId);

        } catch (Exception e) {
            log.warn("injectChargeVariables: skipped for paysId={} — {}", paysId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Salaire de base du collaborateur
    // -----------------------------------------------------------------------

    /** Code habituel de la rubrique « salaire de base » du moteur (strate 1). */
    static final String BASE_SALARY_RUBRIQUE = "SALAIRE_BASE";

    /**
     * Remplace, pour CE calcul, le montant du salaire de base lu dans le jeu de paramètres
     * (une valeur unique pour tout le pays, ex. {@code SALAIRE_BASE_MONTANT = 1200}) par le
     * salaire brut enregistré pour le collaborateur dans « Salaires des collaborateurs »
     * ({@code employee_payroll_config.current_gross_salary}).
     *
     * <p>La rubrique concernée est {@value #BASE_SALARY_RUBRIQUE} si elle existe, sinon la
     * première rubrique de strate 1 en mode {@code MONTANT_FIXE} dans l'ordre du bulletin : le
     * code est proposé à partir du libellé (« Salaire de base » → SALAIRE_DE_BASE), il ne doit
     * pas décider du résultat. Sa clé de montant est surchargée dans la copie des paramètres
     * propre à ce calcul (le jeu de paramètres enregistré n'est pas modifié). Sans
     * configuration ou sans salaire saisi, la valeur du jeu de paramètres reste utilisée.
     */
    private void applyEmployeeBaseSalary(ExecutionContext ctx, List<PayrollRubriqueDef> rubriques, Long employeeId) {
        if (employeeId == null) return;
        BigDecimal gross = employeeConfigRepo.findByProfileUserId(employeeId)
            .map(c -> c.getCurrentGrossSalary())
            .filter(g -> g != null && g.signum() > 0)
            .orElse(null);
        if (gross == null) {
            log.info("applyEmployeeBaseSalary: pas de salaire configuré pour l'employé {} — montant du jeu de paramètres", employeeId);
            return;
        }
        baseSalaryRubrique(rubriques)
            .ifPresent(r -> {
                ctx.getParameters().put(r.getParamKeyTaux(), gross);
                log.debug("applyEmployeeBaseSalary: employé {} — {} = {}", employeeId, r.getParamKeyTaux(), gross);
            });
    }

    /** {@value #BASE_SALARY_RUBRIQUE}, sinon le premier montant fixe de strate 1 ({@code rubriques} est trié par ordre). */
    static Optional<PayrollRubriqueDef> baseSalaryRubrique(List<PayrollRubriqueDef> rubriques) {
        List<PayrollRubriqueDef> fixedBase = rubriques.stream()
            .filter(r -> r.getStrate() == 1 && "MONTANT_FIXE".equals(r.getModeCalcul()) && r.getParamKeyTaux() != null)
            .toList();
        return fixedBase.stream()
            .filter(r -> BASE_SALARY_RUBRIQUE.equals(r.getCode()))
            .findFirst()
            .or(() -> fixedBase.stream().findFirst());
    }
}
