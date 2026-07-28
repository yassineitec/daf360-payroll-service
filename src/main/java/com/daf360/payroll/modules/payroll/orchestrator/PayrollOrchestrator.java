package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.dto.RunPayrollResponse;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollRubriqueDefRepository;
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
 */
@Service
public class PayrollOrchestrator {

    private final ContextLoader contextLoader;
    private final SequenceValidator sequenceValidator;
    private final CalculationDispatcher dispatcher;
    private final SystemVariableAggregator aggregator;
    private final ResultPersister persister;
    private final ForexSnapshotService forexService;
    private final PayrollRubriqueDefRepository rubriqueRepo;
    private final PayrollCountryRepository countryRepo;

    public PayrollOrchestrator(ContextLoader contextLoader,
                                SequenceValidator sequenceValidator,
                                CalculationDispatcher dispatcher,
                                SystemVariableAggregator aggregator,
                                ResultPersister persister,
                                ForexSnapshotService forexService,
                                PayrollRubriqueDefRepository rubriqueRepo,
                                PayrollCountryRepository countryRepo) {
        this.contextLoader = contextLoader;
        this.sequenceValidator = sequenceValidator;
        this.dispatcher = dispatcher;
        this.aggregator = aggregator;
        this.persister = persister;
        this.forexService = forexService;
        this.rubriqueRepo = rubriqueRepo;
        this.countryRepo = countryRepo;
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

        for (PayrollRubriqueDef rubrique : rubriques) {
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

        // 8. Build response
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
}
