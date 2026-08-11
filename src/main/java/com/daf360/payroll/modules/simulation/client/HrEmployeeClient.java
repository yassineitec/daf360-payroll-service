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
 * Calls the HR service's internal endpoint to fetch active employee salary data
 * for cohort aggregate simulations. Uses the shared SERVICE_KEY for auth.
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
     * Returns active employees for the given paysId with salary/profile data.
     * Each element has: userId, paysId, contractType, grade, discipline, salaireNetRh.
     */
    public List<HrEmployeeDto> fetchActiveEmployees(Long paysId) {
        String url = appProperties.getHrApiBaseUrl()
                + "/api/internal/payroll-sync/employees?paysId=" + paysId;
        try {
            byte[] body = restClient.get()
                    .uri(url)
                    .header("X-Service-Key", appProperties.getHrServiceKey())
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
     * Used by IndividualSimulationService to hydrate simulation context (C4).
     */
    public Optional<HrEmployeeDto> findEmployeeByUserId(Long userId) {
        String url = appProperties.getHrApiBaseUrl()
                + "/api/internal/payroll-sync/employees/" + userId;
        try {
            byte[] body = restClient.get()
                    .uri(url)
                    .header("X-Service-Key", appProperties.getHrServiceKey())
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
