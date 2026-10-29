package com.daf360.payroll.modules.ref.service;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private final PayrollCountryRepository payrollCountryRepository;

    @Value("${app.rh-service-url:http://localhost:8888}")
    private String rhServiceUrl;

    @Value("${app.facturation-service-url:http://localhost:8891}")
    private String facturationServiceUrl;

    public ProfileSyncService(RestTemplate restTemplate,
                              PaysRefRepository paysRefRepository,
                              UsersRefRepository usersRefRepository,
                              AppProperties appProperties,
                              PayrollCountryRepository payrollCountryRepository) {
        this.restTemplate = restTemplate;
        this.paysRefRepository = paysRefRepository;
        this.usersRefRepository = usersRefRepository;
        this.appProperties = appProperties;
        this.payrollCountryRepository = payrollCountryRepository;
    }

    @Scheduled(fixedDelay = 15 * 60 * 1_000, initialDelay = 5_000)
    public void sync() {
        syncPays();
        syncUsers();
        syncEntityCurrencies();
    }

    void syncPays() {
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

            // RH's [dbo].[pays] has no currency column, so `devise` is always null on this
            // response by contract (see RH's own PaysForSyncDto doc comment) -- and
            // pays_ref.devise is NOT NULL at the DB level. Writing that null straight through
            // would fail the NOT NULL constraint on every single row, every 15 minutes, which
            // would silently break this entire RH sync (iso_code/french_label included), not
            // just leave a currency stale. So this step never invents or blanks a currency: it
            // preserves whatever devise is already on the row (set by syncEntityCurrencies()
            // below for the 13 ARX entities, or "" for a country never seen before) --
            // syncPays() itself just stops guessing a value, it does not erase one.
            List<Long> ids = response.getBody().stream().map(m -> toLong(m.get("id"))).toList();
            Map<Long, String> existingDevise = paysRefRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(PaysRef::getId, PaysRef::getDevise));

            List<PaysRef> entities = response.getBody().stream().map(m -> {
                PaysRef p = new PaysRef();
                Long id = toLong(m.get("id"));
                p.setId(id);
                p.setIsoCode((String) m.get("isoCode"));
                p.setFrenchLabel((String) m.get("frenchLabel"));
                String devise = (String) m.get("devise");
                p.setDevise(devise != null && !devise.isBlank() ? devise : existingDevise.getOrDefault(id, ""));
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
                // Defaults to true when an older rh-service does not send the field, rather
                // than emptying every picker at once.
                u.setIsEmployee(m.get("isEmployee") == null || Boolean.TRUE.equals(m.get("isEmployee")));
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
     * Polls facturation's entities-for-sync endpoint (the 13 real ARX entities) the same way
     * sync() already polls RH, and keeps two things current from it:
     *  - payroll_countries.currency_code: upsert by paysId, touching only that one column --
     *    every other field (eur_rate, usd_rate, chf_rate, fiscal_year_start_month,
     *    forex_api_sources, active) is left exactly as it is. A brand-new row is inserted with
     *    active = false: a payroll admin must still deliberately onboard the country before the
     *    engine uses it.
     *  - pays_ref.devise (the shadow/display table, separate from payroll_countries): set
     *    directly from the synced value instead of the old app.fx-rates YAML fallback.
     */
    void syncEntityCurrencies() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    facturationServiceUrl + "/api/fact/ref/entities-for-sync",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});

            List<Map<String, Object>> entities = response.getBody();
            if (entities == null || entities.isEmpty()) {
                log.warn("entities-for-sync: {} returned no entity — payroll_countries left untouched",
                        facturationServiceUrl + "/api/fact/ref/entities-for-sync");
                return;
            }

            List<PaysRef> paysRefUpdates = new ArrayList<>();
            for (Map<String, Object> m : entities) {
                Long paysId = toLong(m.get("id"));
                String devise = (String) m.get("devise");
                if (paysId == null || devise == null || devise.isBlank()) continue;

                PayrollCountry country = payrollCountryRepository.findByPaysId(paysId)
                        .orElseGet(() -> {
                            PayrollCountry fresh = new PayrollCountry();
                            fresh.setPaysId(paysId);
                            fresh.setActive(false);
                            return fresh;
                        });
                country.setCurrencyCode(devise);
                payrollCountryRepository.save(country);

                // CORRECT approach -- load the existing row and mutate only devise. Do NOT
                // construct a bare `new PaysRef()` with only id/isoCode/devise set and saveAll()
                // it -- JPA's save() on a detached entity does a full column overwrite, not a
                // field-level merge, so that would silently blank frenchLabel/isoCode on every
                // entity row every 15 minutes.
                paysRefRepository.findById(paysId).ifPresent(existingPays -> {
                    existingPays.setDevise(devise);
                    paysRefUpdates.add(existingPays);
                });
            }
            // Only touches the rows returned by this endpoint (the 13 ARX entities) -- syncPays()
            // above already handles the full pays_ref refresh from RH separately.
            if (!paysRefUpdates.isEmpty()) {
                paysRefRepository.saveAll(paysRefUpdates);
            }
            log.info("Synced currency for {} entities from facturation-service", entities.size());
        } catch (Exception e) {
            log.warn("entity currency sync failed against {}: {}",
                    facturationServiceUrl + "/api/fact/ref/entities-for-sync", e.getMessage());
        }
    }
}
