package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RunPayrollRequest;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class ContextLoader {

    private final PayrollCountryRepository countryRepo;
    private final PayrollParamSetRepository paramSetRepo;
    private final ObjectMapper objectMapper;

    public ContextLoader(PayrollCountryRepository countryRepo,
                         PayrollParamSetRepository paramSetRepo,
                         ObjectMapper objectMapper) {
        this.countryRepo = countryRepo;
        this.paramSetRepo = paramSetRepo;
        this.objectMapper = objectMapper;
    }

    public ExecutionContext load(RunPayrollRequest request) {
        // 1. Find active PayrollCountry for paysId
        PayrollCountry country = countryRepo.findByPaysIdAndActiveTrue(request.getPaysId())
            .orElseThrow(() -> new IllegalStateException(
                "No active PayrollCountry for paysId=" + request.getPaysId()));

        // 2. Find ACTIVE PayrollParamSet for this country
        PayrollParamSet paramSet = paramSetRepo.findByCountryIdAndStatus(country.getId(), "ACTIVE")
            .orElseThrow(() -> new IllegalStateException(
                "No ACTIVE parameter set for country=" + country.getId()));

        // 3. Parse parameters JSON to Map<String, Object>
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(
                paramSet.getParameters(),
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse parameter set parameters JSON", e);
        }

        // 4. Build context
        BigDecimal joursOuvres = request.getJoursOuvresMois() != null
            ? request.getJoursOuvresMois()
            : BigDecimal.valueOf(22);
        String contractType = request.getContractTypeCode() != null
            ? request.getContractTypeCode()
            : "CDI";

        ExecutionContext ctx = new ExecutionContext(
            request.getEmployeeId(), country.getId(), request.getPaysId(),
            request.getPeriodYear(), request.getPeriodMonth(),
            contractType, joursOuvres, params
        );

        // 5. Pre-load system variables
        ctx.putVariable("JOURS_OUVRES", joursOuvres);
        ctx.putVariable("MOIS", BigDecimal.valueOf(request.getPeriodMonth()));
        ctx.putVariable("ANNEE", BigDecimal.valueOf(request.getPeriodYear()));

        return ctx;
    }

    public Long resolveParamSetId(Long paysId) {
        PayrollCountry country = countryRepo.findByPaysIdAndActiveTrue(paysId)
            .orElseThrow(() -> new IllegalStateException(
                "No active PayrollCountry for paysId=" + paysId));
        return paramSetRepo.findByCountryIdAndStatus(country.getId(), "ACTIVE")
            .orElseThrow(() -> new IllegalStateException(
                "No ACTIVE parameter set for country=" + country.getId()))
            .getId();
    }
}
