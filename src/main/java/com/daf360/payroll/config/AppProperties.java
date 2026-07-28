package com.daf360.payroll.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String jwtSecret;
    private String jwtPublicKeyPath = "";
    private List<String> allowedOrigins = List.of("http://localhost:8080", "http://localhost:4205", "http://localhost:4200");
    private String hrApiBaseUrl = "http://daf360-rh-backend:8888";
    private boolean mailEnabled = false;
    private String mailFrom = "noreply@daf360.com";
    private boolean jwtDisabled = false;

    /** FX rates keyed by paysId (Long as String): local currency code, eur, and usd. */
    private Map<String, FxRateEntry> fxRates = Map.of();

    @Data
    public static class FxRateEntry {
        private String currency = "";       // local ISO code: TND, EGP, EUR, SAR, AED …
        private BigDecimal eur = BigDecimal.ONE;
        private BigDecimal usd = BigDecimal.ONE;
    }
}
