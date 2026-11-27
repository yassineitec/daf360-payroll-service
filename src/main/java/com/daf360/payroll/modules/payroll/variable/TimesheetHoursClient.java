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
 * The timesheet's {@code GET /internal/payroll/daily-hours?userId=&from=&to=}
 * (InternalPayrollHoursController): APPROVED hours per day of one collaborator, keyed on the
 * RH user id. Base URL {@code app.timesheet-api-base-url} (includes /api/v1); same
 * {@code X-Internal-Key} as RH.
 *
 * Empty = not configured or unreachable — distinct from an empty LIST (reachable, nothing
 * approved), so the payslip can say « heures supplémentaires non lues » instead of showing a
 * confident zero.
 */
@Slf4j
@Component
public class TimesheetHoursClient {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TimesheetHoursClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(15_000);
        this.restClient = RestClient.builder().requestFactory(f).build();
    }

    public boolean isConfigured() {
        String base = appProperties.getTimesheetApiBaseUrl();
        return base != null && !base.isBlank();
    }

    public Optional<List<DailyHours>> approvedDailyHours(Long userId, LocalDate from, LocalDate to) {
        if (!isConfigured()) return Optional.empty();
        String base = appProperties.getTimesheetApiBaseUrl().replaceAll("/+$", "");
        String url = base + "/internal/payroll/daily-hours?userId=" + userId + "&from=" + from + "&to=" + to;
        try {
            byte[] body = restClient.get()
                    .uri(url)
                    .header("X-Internal-Key", appProperties.getHrInternalApiKey())
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) return Optional.of(List.of());
            return Optional.of(objectMapper.readValue(body, new TypeReference<List<DailyHours>>() {}));
        } catch (Exception ex) {
            log.warn("Timesheet daily hours unavailable for userId={} {}..{}: {}", userId, from, to, ex.getMessage());
            return Optional.empty();
        }
    }

    public record DailyHours(LocalDate date, BigDecimal hours) {}
}
