package com.daf360.payroll.modules.ref.service;

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

    @Value("${app.rh-service-url:http://localhost:8888}")
    private String rhServiceUrl;

    public ProfileSyncService(RestTemplate restTemplate,
                              PaysRefRepository paysRefRepository,
                              UsersRefRepository usersRefRepository) {
        this.restTemplate = restTemplate;
        this.paysRefRepository = paysRefRepository;
        this.usersRefRepository = usersRefRepository;
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

            if (response.getBody() == null) return;

            List<PaysRef> entities = response.getBody().stream().map(m -> {
                PaysRef p = new PaysRef();
                p.setId(toLong(m.get("id")));
                p.setIsoCode((String) m.get("isoCode"));
                p.setFrenchLabel((String) m.get("frenchLabel"));
                p.setDevise((String) m.getOrDefault("devise", ""));
                return p;
            }).toList();

            paysRefRepository.saveAll(entities);
            log.debug("Synced {} pays entries", entities.size());
        } catch (Exception e) {
            log.warn("pays-ref sync failed: {}", e.getMessage());
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
}
