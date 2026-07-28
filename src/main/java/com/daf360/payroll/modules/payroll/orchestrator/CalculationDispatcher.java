package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.calculator.PayrollCalculator;
import com.daf360.payroll.modules.payroll.calculator.RubriqueSpec;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Routes each rubrique to the correct {@link PayrollCalculator} implementation
 * based on its modeCalcul, then applies prorata and contract-type filtering.
 */
@Service
public class CalculationDispatcher {

    private final Map<String, PayrollCalculator> calculators;

    public CalculationDispatcher(List<PayrollCalculator> calculatorList) {
        this.calculators = calculatorList.stream()
            .collect(Collectors.toMap(PayrollCalculator::modeCalcul, c -> c));
    }

    public BigDecimal dispatch(PayrollRubriqueDef rubrique, ExecutionContext context) {
        PayrollCalculator calc = calculators.get(rubrique.getModeCalcul());
        if (calc == null) {
            throw new IllegalStateException(
                "No calculator for mode_calcul=" + rubrique.getModeCalcul());
        }

        // Resolve assiette from context
        BigDecimal assiette = rubrique.getAssietteCode() != null
            ? context.getVariable(rubrique.getAssietteCode())
            : BigDecimal.ZERO;

        // Build RubriqueSpec
        RubriqueSpec spec = RubriqueSpec.builder()
            .code(rubrique.getCode())
            .nature(rubrique.getNature())
            .modeCalcul(rubrique.getModeCalcul())
            .assietteCode(rubrique.getAssietteCode())
            .paramKeyTaux(rubrique.getParamKeyTaux())
            .paramKeyPlafond(rubrique.getParamKeyPlafond())
            .paramKeyBareme(rubrique.getParamKeyBareme())
            .formulaExpression(rubrique.getFormulaExpression())
            .contractTypeFilter(rubrique.getContractTypeFilter())
            .periodicite(rubrique.getPeriodicite())
            .prorataApplicable(rubrique.isProrataApplicable())
            .build();

        BigDecimal result = calc.calculate(assiette, spec, context);

        // Apply prorata if applicable
        if (rubrique.isProrataApplicable()) {
            BigDecimal joursOuvres = context.getJoursOuvresMois();
            if (joursOuvres != null && joursOuvres.compareTo(BigDecimal.ZERO) > 0) {
                result = result
                    .multiply(joursOuvres)
                    .divide(BigDecimal.valueOf(22), 2, RoundingMode.HALF_UP);
            }
        }

        // Contract type filter — return ZERO if the employee's contract type is not allowed
        if (rubrique.getContractTypeFilter() != null) {
            Set<String> allowed = Set.of(rubrique.getContractTypeFilter().split(","));
            if (!allowed.contains(context.getContractTypeCode())) {
                return BigDecimal.ZERO;
            }
        }

        return result;
    }
}
