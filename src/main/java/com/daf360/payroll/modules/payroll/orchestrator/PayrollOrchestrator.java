package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.engine.TopologicalEvaluator;
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

    public PayrollOrchestrator(ContextLoader contextLoader,
                                SequenceValidator sequenceValidator,
                                CalculationDispatcher dispatcher,
                                SystemVariableAggregator aggregator,
                                ResultPersister persister,
                                ForexSnapshotService forexService,
                                PayrollRubriqueDefRepository rubriqueRepo,
                                PayrollCountryRepository countryRepo,
                                TopologicalEvaluator topologicalEvaluator,
                                ParameterSetService parameterSetService) {
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

        // 8. Build response — inject charges for any country where all rubriques are strate-1
        // (edge case: no strate-2+ rubrique exists, so the loop hook never fired)
        if (!chargesInjected && ctx.hasVariable("BRUT")) {
            injectChargeVariables(ctx, request.getPaysId(), contractType, joursTravailes);
        }

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
}
