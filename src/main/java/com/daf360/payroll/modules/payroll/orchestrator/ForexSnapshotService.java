package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollForexSnapshot;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollForexSnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches live exchange rates from the country's configured forex API sources
 * and persists immutable snapshots for audit traceability.
 */
@Service
public class ForexSnapshotService {

    private final PayrollCountryRepository countryRepo;
    private final PayrollForexSnapshotRepository snapshotRepo;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ForexSnapshotService(PayrollCountryRepository countryRepo,
                                 PayrollForexSnapshotRepository snapshotRepo,
                                 RestTemplate restTemplate,
                                 ObjectMapper objectMapper) {
        this.countryRepo = countryRepo;
        this.snapshotRepo = snapshotRepo;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * For a given payroll result (already saved), fetches forex rates from the
     * country's configured forexApiSources and persists immutable snapshots.
     *
     * @return the list of saved snapshots (empty if the country has no sources configured)
     */
    public List<PayrollForexSnapshot> fetchAndPersist(Long payrollResultId, Long paysId) {
        PayrollCountry country = countryRepo.findByPaysIdAndActiveTrue(paysId).orElse(null);
        if (country == null || country.getForexApiSources() == null) {
            return List.of();
        }

        List<Map<String, Object>> sources;
        try {
            sources = objectMapper.readValue(
                country.getForexApiSources(),
                new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }

        List<PayrollForexSnapshot> saved = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            String currency = (String) source.get("currency");
            String url      = (String) source.get("url");
            if (currency == null || url == null) {
                continue;
            }

            int httpCode = 0;
            double rate  = 0.0;
            try {
                ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<Map<String, Object>>() {});
                httpCode = resp.getStatusCode().value();
                Object rateObj = resp.getBody() != null ? resp.getBody().get("rate") : null;
                if (rateObj instanceof Number) {
                    rate = ((Number) rateObj).doubleValue();
                }
            } catch (Exception e) {
                httpCode = 0;
            }

            if (rate > 0) {
                PayrollForexSnapshot snap = new PayrollForexSnapshot();
                snap.setPayrollResultId(payrollResultId);
                snap.setFromCurrency(country.getCurrencyCode());
                snap.setToCurrency(currency);
                snap.setRate(BigDecimal.valueOf(rate).setScale(8, RoundingMode.HALF_UP));
                snap.setSourceName((String) source.getOrDefault("name", url));
                snap.setSourceHttpCode(httpCode);
                snap.setFetchedAt(OffsetDateTime.now());
                saved.add(snapshotRepo.save(snap));
            }
        }
        return saved;
    }
}
