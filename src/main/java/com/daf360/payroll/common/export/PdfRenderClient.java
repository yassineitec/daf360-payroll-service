package com.daf360.payroll.common.export;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.simulation.service.PdfGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * HTML → PDF par le pdf-service ({@code POST /pdf/api/render}, Playwright/Chromium) — même
 * appel que {@code SimulationPdfService} et que les exports PDF de la facturation. Un échec
 * lève {@link PdfGenerationException}, renvoyé en 503 par {@code GlobalExceptionHandler}.
 */
@Slf4j
@Component
public class PdfRenderClient {

    private final AppProperties appProperties;
    private final RestClient restClient;

    public PdfRenderClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(60_000);
        this.restClient = RestClient.builder().requestFactory(f).build();
    }

    public byte[] render(String html, String filename) {
        String url = appProperties.getPdfServiceUrl() + "/pdf/api/render";
        try {
            byte[] bytes = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("html", html, "filename", filename))
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new PdfGenerationException("Réponse vide du service de génération PDF");
            }
            return bytes;
        } catch (RestClientException ex) {
            log.warn("pdf-service unavailable for {} ({}): {}", filename, url, ex.getMessage());
            throw new PdfGenerationException("Service de génération PDF indisponible", ex);
        }
    }
}
