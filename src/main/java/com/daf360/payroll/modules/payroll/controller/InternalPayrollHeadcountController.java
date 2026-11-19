package com.daf360.payroll.modules.payroll.controller;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Service-to-service read of the monthly payroll headcount, for facturation's P&amp;L workbook
 * ("Average FTE" = number of salaries paid that month). Authenticated by the shared
 * {@code X-Internal-Key} secret, like rh-service's /api/hr/internal/** endpoints; a missing or
 * blank key fails closed with 403. Read-only.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalPayrollHeadcountController {

    private static final String HEADER = "X-Internal-Key";

    private final PayrollResultRepository resultRepo;
    private final AppProperties appProperties;

    /**
     * GET /api/payroll/internal/headcount?paysId=179&amp;year=2026
     * {@code {"11": 2, ...}} -- month (1-12) to number of distinct employees with a payroll
     * result that month. Months never calculated are absent.
     */
    @GetMapping("/api/payroll/internal/headcount")
    public Map<Integer, Long> headcountByMonth(
            @RequestParam Long paysId,
            @RequestParam int year,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);
        Map<Integer, Long> byMonth = new TreeMap<>();
        for (Object[] row : resultRepo.countEmployeesByMonth(paysId, year)) {
            byMonth.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return byMonth;
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
