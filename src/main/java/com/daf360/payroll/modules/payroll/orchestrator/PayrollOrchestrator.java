package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.engine.TopologicalEvaluator;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.employeeconfig.repository.EmployeePayrollConfigRepository;
import com.daf360.payroll.modules.employeeconfig.service.ContractTypeCodes;
import com.daf360.payroll.modules.employeeconfig.service.EmployeeFamilyResolver;
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
    private final EmployeeFamilyResolver familyResolver;

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
                                EmployeePayrollConfigRepository employeeConfigRepo,
                                EmployeeFamilyResolver familyResolver) {
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
        this.familyResolver = familyResolver;
    }

    /** Everything one calculation produced, before (or instead of) persisting it. */
    private record Computation(ExecutionContext ctx, List<RubriqueResultItem> results, Long paramSetId,
                               boolean convergenceOk, int totalIterations) {
        Integer iterationsOrNull() { return totalIterations > 0 ? totalIterations : null; }
    }

    /** A real payroll run: computes, then INSERTs an immutable {@code payroll_results} row. */
    @Transactional
    public RunPayrollResponse run(RunPayrollRequest request) {
        Computation c = compute(request, null);

        // 5. Persist result — all fields set before the single INSERT; no UPDATE follows
        PayrollResult saved = persister.persist(
            c.ctx(), c.results(), c.paramSetId(),
            request.getTriggeredBy(),
            c.convergenceOk(),
            c.iterationsOrNull()
        );

        // 7. Persist forex snapshots — fire-and-forget; don't fail the run if forex fails
        try {
            forexService.fetchAndPersist(saved.getId(), request.getPaysId());
        } catch (Exception ignored) {
            // forex snapshot failure must never abort a payroll run
        }

        return toResponse(saved, c);
    }

    /**
     * Exactly the calculation {@link #run} performs — same rubriques, same aggregation — but
     * with {@code grossSalary} as the employee's base salary and NOTHING written (no
     * {@code payroll_results} row, no forex snapshot). Backs "Calculer le net" on the
     * employee's payroll configuration (Rémunération tab / payroll employee-config), so the
     * net shown there is the net the payslip will show. {@code resultId} is null.
     */
    @Transactional(readOnly = true)
    public RunPayrollResponse preview(RunPayrollRequest request, BigDecimal grossSalary) {
        if (grossSalary == null || grossSalary.signum() <= 0) {
            throw new IllegalArgumentException("Le salaire brut doit être strictement positif.");
        }
        Computation c = compute(request, grossSalary);
        PayrollResult unsaved = persister.build(
            c.ctx(), c.results(), c.paramSetId(), request.getTriggeredBy(),
            c.convergenceOk(), c.iterationsOrNull());
        return toResponse(unsaved, c);
    }

    /**
     * @param grossOverride null = a real run: the employee's saved configuration supplies the
     *                      base salary and must exist, be for this country and carry a gross.
     *                      Non-null = a preview with that (working-currency) base salary.
     */
    private Computation compute(RunPayrollRequest request, BigDecimal grossOverride) {
        EmployeePayrollConfig config = request.getEmployeeId() == null ? null
            : employeeConfigRepo.findByProfileUserId(request.getEmployeeId()).orElse(null);

        // Contract: the caller's explicit choice, otherwise the one saved on the employee's
        // payroll configuration (which may deliberately differ from the RH profile), otherwise
        // CDI. Always normalised (legacy RH codes PERMANENT/FIXED_TERM/INTERN → CDI/CDD/STAGE).
        String requested = request.getContractTypeCode();
        if ((requested == null || requested.isBlank()) && config != null) requested = config.getContractType();
        request.setContractTypeCode(ContractTypeCodes.normalize(requested));

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
        //     montant unique du jeu de paramètres — voir applyEmployeeInputs.
        applyEmployeeInputs(ctx, rubriques, request.getEmployeeId(), config, grossOverride);

        // 4. Execute each rubrique in order
        List<RubriqueResultItem> results = new ArrayList<>();
        boolean convergenceOk = true;
        int totalIterations = 0;

        String contractType = request.getContractTypeCode();
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
                .payer(rubrique.getPayer())
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
        }

        return new Computation(ctx, results, paramSetId, convergenceOk, totalIterations);
    }

    /** Response of a run or a preview (charges déjà injectées à l'étape 4b si besoin). */
    private RunPayrollResponse toResponse(PayrollResult saved, Computation c) {
        boolean convergenceOk = c.convergenceOk();
        int totalIterations = c.totalIterations();
        List<RubriqueResultItem> results = c.results();
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
     * propre à ce calcul (le jeu de paramètres enregistré n'est pas modifié).
     *
     * <p>Calcul réel ({@code grossOverride} nul) : le collaborateur DOIT avoir une
     * configuration enregistrée pour CE pays, avec un salaire brut — sinon le calcul est
     * refusé. Avant, il retombait en silence sur le montant unique du pays (ex. 2 500 TND
     * pour tout le monde), un bulletin faux sans aucun signal.
     *
     * <p>Situation familiale (IS_MARRIED / CHILDREN_COUNT, déduction IRPP) : lue sur la fiche
     * RH, source de vérité — la copie de la configuration ne sert que si RH est injoignable.
     */
    private void applyEmployeeInputs(ExecutionContext ctx, List<PayrollRubriqueDef> rubriques, Long employeeId,
                                     EmployeePayrollConfig config, BigDecimal grossOverride) {
        if (employeeId == null) return;

        EmployeeFamilyResolver.FamilySituation family = familyResolver.resolve(employeeId, config);
        if (!family.fromHr()) {
            log.warn("applyEmployeeInputs: situation familiale de l'employé {} non lue sur la fiche RH — valeur en cache utilisée", employeeId);
        }
        ctx.putVariable("IS_MARRIED", family.married() ? BigDecimal.ONE : BigDecimal.ZERO);
        ctx.putVariable("CHILDREN_COUNT", BigDecimal.valueOf(family.childrenCount()));

        BigDecimal gross;
        if (grossOverride != null) {
            gross = grossOverride;
        } else {
            if (config != null && config.getPaysId() != null && !config.getPaysId().equals(ctx.getPaysId())) {
                throw new IllegalStateException("La configuration de paie du collaborateur " + employeeId
                    + " est enregistrée pour un autre pays (paysId=" + config.getPaysId()
                    + ") : son salaire n'est pas exprimé dans la devise de ce calcul (paysId=" + ctx.getPaysId()
                    + "). Mettez à jour sa configuration avant de lancer le calcul.");
            }
            gross = config != null ? config.getCurrentGrossSalary() : null;
            // Sans salaire brut enregistré, le calcul est REFUSÉ (400) au lieu de retomber sur le
            // montant unique du jeu de paramètres. Choix métier du 2026-11-10.
            if (gross == null || gross.signum() <= 0) {
                log.info("applyEmployeeInputs: pas de salaire configuré pour l'employé {} — calcul refusé", employeeId);
                throw new IllegalArgumentException(
                    "Salaire non renseigné pour ce collaborateur : saisissez son salaire brut dans "
                    + "« Salaires des collaborateurs » avant de lancer le calcul.");
            }
        }

        Optional<PayrollRubriqueDef> base = baseSalaryRubrique(rubriques);
        if (base.isEmpty()) {
            throw new IllegalStateException("Aucune rubrique « salaire de base » (strate 1, montant fixe) "
                + "dans les règles de paie de ce pays : le salaire du collaborateur ne peut pas être appliqué.");
        }
        ctx.getParameters().put(base.get().getParamKeyTaux(), gross);
        log.debug("applyEmployeeInputs: employé {} — {} = {}", employeeId, base.get().getParamKeyTaux(), gross);
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
