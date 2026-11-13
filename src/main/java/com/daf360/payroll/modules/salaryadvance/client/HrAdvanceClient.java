package com.daf360.payroll.modules.salaryadvance.client;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.salaryadvance.dto.SalaryAdvanceDtos.ContractFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What payroll asks RH for, for salary advances — rh-service's InternalSalaryAdvanceController,
 * authenticated by the {@code X-Internal-Key} shared secret ({@code app.hr-internal-api-key},
 * the same value as rh's INTERNAL_API_KEY).
 *
 *  - {@link #employee}: the facts behind the eligibility rules (entity, hire date, status,
 *    departure in progress) and the payroll month in the entity's zone. Empty when RH says the
 *    person has no employee profile; an exception when RH cannot be reached — the caller must
 *    not read "RH is down" as "not an employee".
 *  - {@link #employees}: the same, batched — names and matricules for the monthly export.
 *    Fails soft (empty), the export then prints blanks rather than failing.
 *  - {@link #notify}: dispatches one salary-advance notification through RH's routing engine.
 *    Fails soft: a notification must never undo a decision.
 */
@Slf4j
@Component
public class HrAdvanceClient {

    private static final String HEADER = "X-Internal-Key";

    private final AppProperties appProperties;
    private final ObjectMapper  objectMapper;
    private final RestClient    restClient;

    public HrAdvanceClient(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper  = objectMapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(8_000);
        this.restClient = RestClient.builder().requestFactory(f).build();
    }

    /** @throws IllegalStateException when RH cannot be reached or refuses the key. */
    public Optional<EmployeeFacts> employee(Long userId) {
        String url = base() + "/api/hr/internal/salary-advances/employees/" + userId;
        try {
            byte[] body = restClient.get().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .retrieve().body(byte[].class);
            if (body == null || body.length == 0) return Optional.empty();   // 204: no profile
            return Optional.of(objectMapper.readValue(body, EmployeeFacts.class));
        } catch (Exception ex) {
            log.warn("RH employee facts unavailable for userId={}: {}", userId, ex.getMessage());
            throw new IllegalStateException("Le service RH est indisponible — réessayez dans un instant");
        }
    }

    public Map<Long, EmployeeFacts> employees(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String url = base() + "/api/hr/internal/salary-advances/employees?userIds=" + ids;
        try {
            byte[] body = restClient.get().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .retrieve().body(byte[].class);
            if (body == null || body.length == 0) return Map.of();
            List<EmployeeFacts> list = objectMapper.readValue(body, new TypeReference<>() {});
            return list.stream().collect(Collectors.toMap(EmployeeFacts::userId, f -> f, (a, b) -> a));
        } catch (Exception ex) {
            log.warn("RH employee facts batch unavailable ({} ids): {}", userIds.size(), ex.getMessage());
            return Map.of();
        }
    }

    public void notify(String eventCode, Long paysId, Long subjectUserId, Long advanceId,
                       Map<String, String> templateVars) {
        String url = base() + "/api/hr/internal/notifications";
        try {
            restClient.post().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NotificationRequest(eventCode, paysId, subjectUserId, advanceId, templateVars))
                    .retrieve().toBodilessEntity();
        } catch (Exception ex) {
            log.warn("RH notification {} for advance {} not sent: {}", eventCode, advanceId, ex.getMessage());
        }
    }

    /**
     * Has RH render the debt contract from the country's template and file it in the
     * employee's RH folder (type CONTRAT_AVANCE_SALAIRE, SharePoint-mirrored). Returns the
     * RH employee_documents id.
     *
     * @throws IllegalStateException when RH cannot be reached or cannot render — the caller
     *         decides whether that blocks (the retry endpoint) or not (the approval).
     */
    public Long generateContract(ContractRequest request) {
        String url = base() + "/api/hr/internal/salary-advances/" + request.advanceId() + "/contract";
        try {
            DocumentRef ref = restClient.post().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve().body(DocumentRef.class);
            if (ref == null || ref.documentId() == null) throw new IllegalStateException("empty answer");
            return ref.documentId();
        } catch (Exception ex) {
            log.warn("RH could not generate the contract of advance {}: {}", request.advanceId(), ex.getMessage());
            throw new IllegalStateException("Le contrat n'a pas pu être généré par le service RH — réessayez dans un instant");
        }
    }

    /** Files the signed, legalised scan in the employee's RH folder. Returns the RH document id. */
    public Long archiveSignedContract(Long advanceId, Long userId, Long uploadedBy,
                                      byte[] content, String fileName, String contentType) {
        String url = base() + "/api/hr/internal/salary-advances/" + advanceId + "/signed-contract";
        // Built by hand, NOT with MultipartBodyBuilder: in Spring 7 that class links to
        // org.reactivestreams.Publisher, absent from this (servlet-only) service — the
        // NoClassDefFoundError escaped the catch below and every upload answered 500 (TC-15).
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        fileHeaders.setContentDisposition(ContentDisposition.formData()
                .name("file").filename(fileName, StandardCharsets.UTF_8).build());
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        }, fileHeaders));
        parts.add("userId", String.valueOf(userId));
        if (uploadedBy != null) parts.add("uploadedBy", String.valueOf(uploadedBy));
        try {
            DocumentRef ref = restClient.post().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve().body(DocumentRef.class);
            if (ref == null || ref.documentId() == null) throw new IllegalStateException("empty answer");
            return ref.documentId();
        } catch (Exception | LinkageError ex) {
            log.warn("RH could not archive the signed contract of advance {}: {}", advanceId, ex.toString(), ex);
            throw new IllegalStateException("Le contrat signé n'a pas pu être archivé par le service RH — réessayez dans un instant");
        }
    }

    /**
     * Streams one contract back from RH. RH checks the document belongs to this employee and
     * is a CONTRAT_AVANCE_SALAIRE — the internal key alone never reads any other document.
     */
    public ContractFile downloadContract(Long userId, Long documentId) {
        String url = base() + "/api/hr/internal/salary-advances/employees/" + userId + "/contracts/" + documentId;
        try {
            ResponseEntity<byte[]> res = restClient.get().uri(url)
                    .header(HEADER, appProperties.getHrInternalApiKey())
                    .retrieve().toEntity(byte[].class);
            byte[] body = res.getBody();
            if (body == null || body.length == 0) throw new IllegalStateException("empty file");
            String name = res.getHeaders().getContentDisposition().getFilename();
            MediaType type = res.getHeaders().getContentType();
            return new ContractFile(body,
                    name != null ? name : "contrat-avance-" + documentId + ".pdf",
                    type != null ? type.toString() : MediaType.APPLICATION_PDF_VALUE);
        } catch (Exception ex) {
            log.warn("RH contract {} of user {} unavailable: {}", documentId, userId, ex.getMessage());
            throw new IllegalStateException("Le contrat est indisponible pour le moment — réessayez dans un instant");
        }
    }

    private String base() {
        return appProperties.getHrApiBaseUrl();
    }

    /** What RH needs to fill the contract template. Months as "yyyy-MM". */
    public record ContractRequest(
            Long                advanceId,
            Long                userId,
            Long                paysId,
            String              currency,
            BigDecimal          grantedAmount,
            Integer             installments,
            BigDecimal          monthlyAmount,
            LocalDate           effectiveDate,
            String              firstDeductionMonth,
            String              lastDeductionMonth,
            List<ScheduleLine>  schedule,
            Long                generatedBy
    ) {}

    public record ScheduleLine(Integer seq, String month, BigDecimal amount) {}

    record DocumentRef(Long documentId, String fileName) {}

    /** Mirror of rh's InternalSalaryAdvanceController.EmployeeFacts. */
    public record EmployeeFacts(
            Long      userId,
            Long      profileId,
            String    fullName,
            Long      paysId,
            LocalDate hireDate,
            String    lifecycleStatus,
            boolean   active,
            boolean   offboardingInProgress,
            String    payrollMatricule,
            String    currentMonth
    ) {}

    record NotificationRequest(
            String              eventCode,
            Long                paysId,
            Long                subjectUserId,
            Long                entityId,
            Map<String, String> templateVars
    ) {}
}
