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
            throw new IllegalStateException("Paramètres du jeu de paramètres : JSON illisible", e);
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

        // 6. Expose every scalar parameter (taux_*, abattement_*, family_* etc.) as a
        //    context variable too, by its own key name — FORMULE-mode rubriques only
        //    read named context variables, never the parameters map directly (unlike
        //    TAUX_PCT/MONTANT_FIXE/ANNUALISE_BAREME, which use param_key_* fields).
        //    List-valued parameters (bareme_*) are skipped: they can't become a single
        //    BigDecimal and aren't needed as one — ANNUALISE_BAREME reads them via
        //    param_key_bareme directly from the parameters map.
        copyScalarParametersAsVariables(ctx, params);

        return ctx;
    }

    /**
     * Note: EngineConfigService.missingKeys() only scans param_key_taux/plafond/bareme
     * across rubriques to warn admins about missing JSON keys before activating a param
     * set. It has no visibility into parameter names embedded directly inside a FORMULE
     * rubrique's formula_expression (like abattement_rate_tn) — activating a param set
     * missing one evaluates to a wrong number silently, not a caught validation error.
     */
    static void copyScalarParametersAsVariables(ExecutionContext ctx, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            BigDecimal value = toScalar(entry.getValue());
            if (value != null) {
                ctx.putVariable(entry.getKey(), value);
            }
        }
    }

    private static BigDecimal toScalar(Object raw) {
        if (raw instanceof BigDecimal v) return v;
        if (raw instanceof Double v) return BigDecimal.valueOf(v);
        if (raw instanceof Integer v) return BigDecimal.valueOf(v.longValue());
        if (raw instanceof Long v) return BigDecimal.valueOf(v);
        if (raw instanceof String v) {
            try {
                return new BigDecimal(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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
