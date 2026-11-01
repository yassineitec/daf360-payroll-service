package com.daf360.payroll.modules.simulation.client;

import com.daf360.payroll.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Calls the HR service's internal endpoints (rh-service's InternalPayrollSyncController) to
 * fetch employee salary/profile data, authenticated by the {@code X-Internal-Key} shared
 * secret ({@code app.hr-internal-api-key}, the same value as rh's INTERNAL_API_KEY) — the
 * same pattern HrAdvanceClient uses for salary advances.
 */
@Slf4j
@Component
public class HrEmployeeClient {

    private final AppProperties appProperties;
    private final ObjectMapper  objectMapper;
    private final RestClient    restClient;

    public HrEmployeeClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper  = objectMapper;

        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(15_000);
        this.restClient = RestClient.builder().requestFactory(f).build();
    }

    /**
     * Returns the country's active workforce (ACTIVE/ON_LEAVE/ON_MISSION) with salary/profile
     * data, for CohortAggregateService's budget projections. Each element has: userId, paysId,
     * contractType, grade, discipline, salaireNetRh.
     *
     * Fails soft to an empty list on any error, matching {@link #findEmployeeByUserId}'s own
     * convention — but note an empty list here reads as a valid "no employees in this cohort"
     * to the caller, not a failure signal. That's fine once this call actually succeeds; it
     * was NOT fine while this endpoint didn't exist on the RH side (a permanent, unnoticed 401
     * masquerading as an always-empty cohort) — fixed alongside {@link #findEmployeeByUserId}.
     */
    public List<HrEmployeeDto> fetchActiveEmployees(Long paysId) {
        String url = appProperties.getHrApiBaseUrl()
                + "/api/hr/internal/payroll-sync/employees?paysId=" + paysId;
        try {
            byte[] body = restClient.get()
                    .uri(url)
                    .header("X-Internal-Key", appProperties.getHrInternalApiKey())
                    .retrieve()
                    .body(byte[].class);

            if (body == null || body.length == 0) return List.of();
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (RestClientException ex) {
            log.warn("HR employee sync failed for paysId={}: {}", paysId, ex.getMessage());
            return List.of();
        } catch (Exception ex) {
            log.error("Unexpected error fetching HR employees for paysId={}", paysId, ex);
            return List.of();
        }
    }

    /**
     * Fetches a single employee's payroll-relevant profile by userId.
     * Returns empty if the employee is not found or the HR service is unavailable.
     * Used by IndividualSimulationService to hydrate simulation context (C4), and by
     * EmployeePayrollConfigService.getOrDefault() to default a never-configured employee's
     * country/contract type (Rémunération tab, "Calculer le net").
     */
    public Optional<HrEmployeeDto> findEmployeeByUserId(Long userId) {
        String url = appProperties.getHrApiBaseUrl()
                + "/api/hr/internal/payroll-sync/employees/" + userId;
        try {
            byte[] body = restClient.get()
                    .uri(url)
                    .header("X-Internal-Key", appProperties.getHrInternalApiKey())
                    .retrieve()
                    .body(byte[].class);

            if (body == null || body.length == 0) return Optional.empty();
            return Optional.of(objectMapper.readValue(body, HrEmployeeDto.class));
        } catch (RestClientException ex) {
            log.warn("HR profile lookup failed for userId={}: {}", userId, ex.getMessage());
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Unexpected error fetching HR profile for userId={}", userId, ex);
            return Optional.empty();
        }
    }

    /** Projection of PayrollEmployeeDto from the HR service. */
    public record HrEmployeeDto(
            Long       userId,
            Long       paysId,
            String     contractType,
            String     grade,
            String     discipline,
            BigDecimal salaireNetRh
    ) {}
}
