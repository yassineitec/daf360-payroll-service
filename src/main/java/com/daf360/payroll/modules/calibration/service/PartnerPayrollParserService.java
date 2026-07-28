package com.daf360.payroll.modules.calibration.service;

import com.daf360.payroll.modules.calibration.dto.PartnerPayrollRow;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a partner payroll CSV into PartnerPayrollRow records.
 *
 * Expected format (header row required):
 *   profileUserId,actualLoadedCost,contractType
 *
 * Lines that cannot be parsed are skipped with a warning log.
 */
@Service
public class PartnerPayrollParserService {

    public List<PartnerPayrollRow> parse(MultipartFile file) {
        List<PartnerPayrollRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String header = reader.readLine();  // skip header
            if (header == null) return rows;

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 2) continue;

                try {
                    Long userId = Long.parseLong(parts[0].trim());
                    BigDecimal cost = new BigDecimal(parts[1].trim());
                    String contractType = parts.length >= 3 ? parts[2].trim() : "CDI";
                    rows.add(new PartnerPayrollRow(userId, cost, contractType, lineNumber));
                } catch (NumberFormatException ignored) {
                    // skip malformed lines
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse partner payroll CSV: " + e.getMessage(), e);
        }
        return rows;
    }
}
