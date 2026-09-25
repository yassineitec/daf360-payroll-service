package com.daf360.payroll.modules.salaryadvance.client;

import com.daf360.payroll.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What payroll asks RH for, for salary advances — rh-service's InternalSalaryAdvanceController,
 * authenticated by the {@code X-Internal-Key} shared secret ({@code app.hr-internal-api-key},
 * the same value as rh's INTERNAL_API_KEY).
 *
 *  - {@link #employee}: the facts behind the eligibility rules (entity, hire date, status,
 *    departure in progress) and the payroll month in the entity's zone. Empty when RH says the
 *    person has no employee profile; an exception when RH cannot be reached — the caller must
 *    not read "RH is down" as "not an employee".
 *  - {@link #employees}: the same, batched — names and matricules for the monthly export.
 *    Fails soft (empty), the export then prints blanks rather than failing.
 *  - {@link #notify}: dispatches one salary-advance notification through RH's routing engine.
 *    Fails soft: a notification must never undo a decision.
 */
@Slf4j
@Component
public class HrAdvanceClient {

    private static final String HEADER = "X-Internal-Key";

    private final AppProperties appProperties;
    private final ObjectMapper  objectMapper;
    private final RestClient    restClient;

    public HrAdvanceClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper  = objectMapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(8_000);
        this.restClient = RestClient.builder().requestFactory(f).build();
    }

    /** @throws IllegalStateException when RH cannot be reached or refuses the key. */
    public Optional<EmployeeFacts> employee(Long userId) {
        String url = base() + "/api/hr/internal/salary-advances/employees/" + userId;
        try {
            byte[] body = restClient.get().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .retrieve().body(byte[].class);
            if (body == null || body.length == 0) return Optional.empty();   // 204: no profile
            return Optional.of(objectMapper.readValue(body, EmployeeFacts.class));
        } catch (Exception ex) {
            log.warn("RH employee facts unavailable for userId={}: {}", userId, ex.getMessage());
            throw new IllegalStateException("Le service RH est indisponible — réessayez dans un instant");
        }
    }

    public Map<Long, EmployeeFacts> employees(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String url = base() + "/api/hr/internal/salary-advances/employees?userIds=" + ids;
        try {
            byte[] body = restClient.get().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .retrieve().body(byte[].class);
            if (body == null || body.length == 0) return Map.of();
            List<EmployeeFacts> list = objectMapper.readValue(body, new TypeReference<>() {});
            return list.stream().collect(Collectors.toMap(EmployeeFacts::userId, f -> f, (a, b) -> a));
        } catch (Exception ex) {
            log.warn("RH employee facts batch unavailable ({} ids): {}", userIds.size(), ex.getMessage());
            return Map.of();
        }
    }

    public void notify(String eventCode, Long paysId, Long subjectUserId, Long advanceId,
                       Map<String, String> templateVars) {
        String url = base() + "/api/hr/internal/notifications";
        try {
            restClient.post().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NotificationRequest(eventCode, paysId, subjectUserId, advanceId, templateVars))
                    .retrieve().toBodilessEntity();
        } catch (Exception ex) {
            log.warn("RH notification {} for advance {} not sent: {}", eventCode, advanceId, ex.getMessage());
        }
    }

    private String base() {
        return appProperties.getHrApiBaseUrl();
    }

    /** Mirror of rh's InternalSalaryAdvanceController.EmployeeFacts. */
    public record EmployeeFacts(
            Long      userId,
            Long      profileId,
            String    fullName,
            Long      paysId,
            LocalDate hireDate,
            String    lifecycleStatus,
            boolean   active,
            boolean   offboardingInProgress,
            String    payrollMatricule,
            String    currentMonth
    ) {}

    record NotificationRequest(
            String              eventCode,
            Long                paysId,
            Long                subjectUserId,
            Long                entityId,
            Map<String, String> templateVars
    ) {}
}
