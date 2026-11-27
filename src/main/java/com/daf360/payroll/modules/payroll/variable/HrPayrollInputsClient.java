package com.daf360.payroll.modules.payroll.variable;

import com.daf360.payroll.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * rh-service's internal payroll endpoints (InternalPayrollInputsController), with
 * {@code X-Internal-Key} = {@code app.hr-internal-api-key}:
 * <ul>
 *   <li>{@code GET /api/hr/internal/payroll-inputs/employees/{userId}?year=&month=&from=&to=}:
 *       for the pay period — country of work, its weekend and holidays, régime hours, entry/exit,
 *       approved absences, approved autorisation minutes with the monthly quota, and the congé /
 *       maladie balances at the start and the end of the period;</li>
 *   <li>{@code GET /api/hr/internal/absence-types}: the leave catalogue, for the payroll admin
 *       screen where each country decides which types cost salary.</li>
 * </ul>
 * Empty = RH unreachable, no HR profile (204), or an older rh-service without the endpoint:
 * the caller records it as a warning instead of failing.
 */
@Slf4j
@Component
public class HrPayrollInputsClient {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HrPayrollInputsClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(15_000);
        this.restClient = RestClient.builder().requestFactory(f).build();
    }

    public Optional<HrPayrollInputs> fetch(Long userId, int year, int month, LocalDate from, LocalDate to) {
        String url = appProperties.getHrApiBaseUrl()
                + "/api/hr/internal/payroll-inputs/employees/" + userId
                + "?year=" + year + "&month=" + month + "&from=" + from + "&to=" + to;
        try {
            byte[] body = get(url);
            if (body == null || body.length == 0) return Optional.empty();
            return Optional.of(objectMapper.readValue(body, HrPayrollInputs.class));
        } catch (Exception ex) {
            log.warn("HR payroll inputs unavailable for userId={} {}..{}: {}", userId, from, to, ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<List<AbsenceTypeItem>> absenceTypes() {
        try {
            byte[] body = get(appProperties.getHrApiBaseUrl() + "/api/hr/internal/absence-types");
            if (body == null || body.length == 0) return Optional.of(List.of());
            return Optional.of(objectMapper.readValue(body, new TypeReference<List<AbsenceTypeItem>>() {}));
        } catch (Exception ex) {
            log.warn("HR leave catalogue unavailable: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private byte[] get(String url) {
        return restClient.get()
                .uri(url)
                .header("X-Internal-Key", appProperties.getHrInternalApiKey())
                .retrieve()
                .body(byte[].class);
    }

    /** Mirrors rh-service's InternalPayrollInputsController.PayrollInputs. */
    public record HrPayrollInputs(
            Long userId,
            Long paysId,
            int year,
            int month,
            LocalDate periodFrom,
            LocalDate periodTo,
            List<String> weekendDays,
            List<LocalDate> holidays,
            long workingDaysInPeriod,
            LocalDate hireDate,
            LocalDate exitDate,
            long workingDaysEmployed,
            String regimeCode,
            BigDecimal hoursPerWeek,
            Integer daysPerWeek,
            List<AbsenceLine> absences,
            int autorisationMinutes,
            Integer autorisationMonthlyQuotaMinutes,
            List<BalanceLine> balances
    ) {}

    public record AbsenceLine(
            String typeCode,
            String labelFr,
            String labelEn,
            String category,
            LocalDate from,
            LocalDate to,
            BigDecimal days,
            /** CONGE / MALADIE when the type draws on a balance, else null. */
            String balanceField
    ) {}

    public record BalanceLine(
            String field,
            BigDecimal atPeriodStart,
            BigDecimal atPeriodEnd,
            BigDecimal takenInPeriod,
            BigDecimal current
    ) {}

    public record AbsenceTypeItem(
            String code,
            String labelFr,
            String labelEn,
            boolean active,
            boolean tracksBalance,
            String balanceField,
            int displayOrder
    ) {}
}
