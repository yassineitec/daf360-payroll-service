package com.daf360.payroll.modules.payroll.calculator;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-calculation value object holding employee/period context and accumulating
 * resolved rubrique variable values as the engine runs each strate.
 */
public class ExecutionContext {

    private final Long employeeId;
    private final Long countryId;
    private final Long paysId;
    private final int periodYear;
    private final int periodMonth;
    private final String contractTypeCode;
    private final BigDecimal joursOuvresMois;
    // mutable: calculators add resolved rubrique values here during the run
    private final Map<String, BigDecimal> variables = new HashMap<>();
    // parsed parameters from PayrollParamSet.parameters (taux, barèmes, etc.)
    private final Map<String, Object> parameters;

    public ExecutionContext(Long employeeId, Long countryId, Long paysId,
                            int periodYear, int periodMonth,
                            String contractTypeCode, BigDecimal joursOuvresMois,
                            Map<String, Object> parameters) {
        this.employeeId = employeeId;
        this.countryId = countryId;
        this.paysId = paysId;
        this.periodYear = periodYear;
        this.periodMonth = periodMonth;
        this.contractTypeCode = contractTypeCode;
        this.joursOuvresMois = joursOuvresMois;
        this.parameters = parameters != null ? parameters : new HashMap<>();
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getCountryId() {
        return countryId;
    }

    public Long getPaysId() {
        return paysId;
    }

    public int getPeriodYear() {
        return periodYear;
    }

    public int getPeriodMonth() {
        return periodMonth;
    }

    public String getContractTypeCode() {
        return contractTypeCode;
    }

    public BigDecimal getJoursOuvresMois() {
        return joursOuvresMois;
    }

    public void putVariable(String name, BigDecimal value) {
        variables.put(name, value);
    }

    public BigDecimal getVariable(String name) {
        return variables.getOrDefault(name, BigDecimal.ZERO);
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    public Map<String, BigDecimal> getVariables() {
        return variables;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }
}
