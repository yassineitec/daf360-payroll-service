package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.ref.entity.PaysRef;
import com.daf360.payroll.modules.ref.entity.UsersRef;
import com.daf360.payroll.modules.ref.repository.PaysRefRepository;
import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Periodically syncs the pays_ref and users_ref shadow tables from the
 * DAF360-RH service so that payroll can resolve paysId and userId without
 * issuing cross-service calls at query time.
 *
 * Runs every 15 minutes.  A startup sync is triggered via @Scheduled fixedDelay.
 */
@Service
public class ProfileSyncService {

    private static final Logger log = LoggerFactory.getLogger(ProfileSyncService.class);

    private final RestTemplate restTemplate;
    private final PaysRefRepository paysRefRepository;
    private final UsersRefRepository usersRefRepository;
    private final AppProperties appProperties;

    @Value("${app.rh-service-url:http://localhost:8888}")
    private String rhServiceUrl;

    public ProfileSyncService(RestTemplate restTemplate,
                              PaysRefRepository paysRefRepository,
                              UsersRefRepository usersRefRepository,
                              AppProperties appProperties) {
        this.restTemplate = restTemplate;
        this.paysRefRepository = paysRefRepository;
        this.usersRefRepository = usersRefRepository;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelay = 15 * 60 * 1_000, initialDelay = 5_000)
    public void sync() {
        syncPays();
        syncUsers();
    }

    private void syncPays() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    rhServiceUrl + "/api/hr/pays-for-sync",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});

            if (response.getBody() == null || response.getBody().isEmpty()) {
                // WARN, not silence: an empty pays_ref blanks the country dropdown on
                // every payroll screen, and the page has no way to tell that apart from
                // "no entity configured".
                log.warn("pays-ref sync: {} returned no entity — pays_ref left untouched",
                        rhServiceUrl + "/api/hr/pays-for-sync");
                return;
            }

            List<PaysRef> entities = response.getBody().stream().map(m -> {
                PaysRef p = new PaysRef();
                p.setId(toLong(m.get("id")));
                p.setIsoCode((String) m.get("isoCode"));
                p.setFrenchLabel((String) m.get("frenchLabel"));
                p.setDevise(resolveDevise(m.get("devise"), p.getId()));
                return p;
            }).toList();

            paysRefRepository.saveAll(entities);
            log.info("Synced {} pays entries into pays_ref", entities.size());
        } catch (Exception e) {
            log.warn("pays-ref sync failed against {}: {}",
                    rhServiceUrl + "/api/hr/pays-for-sync", e.getMessage());
        }
    }

    private void syncUsers() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    rhServiceUrl + "/api/hr/users-for-sync",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});

            if (response.getBody() == null) return;

            List<UsersRef> entities = response.getBody().stream().map(m -> {
                UsersRef u = new UsersRef();
                u.setId(toLong(m.get("id")));
                u.setAzureOid((String) m.get("azureOid"));
                u.setFullName((String) m.getOrDefault("fullName", ""));
                u.setEmail((String) m.get("email"));
                u.setPaysId(toLong(m.get("paysId")));
                u.setRoleName((String) m.get("roleName"));
                return u;
            }).toList();

            usersRefRepository.saveAll(entities);
            log.debug("Synced {} user entries", entities.size());
        } catch (Exception e) {
            log.warn("users-ref sync failed: {}", e.getMessage());
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    /**
     * Currency of an entity. RH has no currency column on `[dbo].[pays]`, so it always
     * sends null — but `pays_ref.devise` is NOT NULL, and the frontend shows it next to
     * the amount fields *before* a simulation exists (afterwards the result's own
     * `localCurrency` takes over). Payroll already knows the currency per entity through
     * `app.fx-rates`, which is exactly what the engine itself uses, so that is the
     * fallback. Empty string only when the entity is in neither.
     */
    private String resolveDevise(Object fromRh, Long paysId) {
        if (fromRh instanceof String s && !s.isBlank()) return s;
        if (paysId == null) return "";
        return Optional.ofNullable(appProperties.getFxRates())
                .map(m -> m.get(String.valueOf(paysId)))
                .map(AppProperties.FxRateEntry::getCurrency)
                .filter(c -> !c.isBlank())
                .orElse("");
    }
}
