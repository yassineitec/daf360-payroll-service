package com.daf360.payroll.modules.payroll.controller;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service-to-service read of each employee's loaded cost (H.10) per month, for facturation's
 * F.04 loaded-cost-rate computation. employeeId is the HR employee_profiles.id. A re-run month
 * counts once: the latest calculation per employee and month wins. Authenticated by the
 * shared {@code X-Internal-Key} secret; a missing or blank key fails closed with 403.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalPayrollLoadedCostController {

    private static final String HEADER = "X-Internal-Key";

    private final PayrollResultRepository resultRepo;
    private final PayrollCountryRepository countryRepo;
    private final AppProperties appProperties;

    public record LoadedCostRow(Long employeeId, int periodMonth, BigDecimal loadedCost, String currency) {}

    /** GET /api/payroll/internal/loaded-costs?paysId=179&amp;year=2026&amp;months=1,2,3 */
    @GetMapping("/api/payroll/internal/loaded-costs")
    public List<LoadedCostRow> loadedCosts(
            @RequestParam Long paysId,
            @RequestParam int year,
            @RequestParam List<Integer> months,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);
        if (months == null || months.isEmpty()) return List.of();
        int from = year * 100 + Collections.min(months);
        int to = year * 100 + Collections.max(months);
        Map<Integer, List<PayrollResult>> byMonth = resultRepo.findByPaysIdAndPeriodKeyBetween(paysId, from, to)
                .stream()
                .filter(r -> months.contains(r.getPeriodMonth()))
                .collect(Collectors.groupingBy(PayrollResult::getPeriodMonth));

        Map<Long, String> currencyByCountry = new HashMap<>();
        List<LoadedCostRow> rows = new ArrayList<>();
        byMonth.forEach((month, monthRows) -> {
            for (PayrollResult r : PayrollResultRepository.latestPerEmployee(monthRows)) {
                String currency = currencyByCountry.computeIfAbsent(r.getCountryId(),
                        id -> id == null ? null : countryRepo.findById(id).map(PayrollCountry::getCurrencyCode).orElse(null));
                rows.add(new LoadedCostRow(r.getEmployeeId(), month, r.getLoadedCost(), currency));
            }
        });
        return rows;
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new AccessDeniedException("Internal API disabled");
        }
        if (provided == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Invalid internal key");
        }
    }
}
