package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.config.AppProperties;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Builds a printable HTML simulation sheet and converts it to PDF via the pdf-service.
 * If the pdf-service is unavailable the HTML bytes are returned directly so the caller
 * can serve them as text/html for browser-side printing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationPdfService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AppProperties appProperties;
    private final SimulationResultRepository resultRepo;

    private final RestClient restClient = RestClient.builder()
            .requestFactory(buildFactory())
            .build();

    private static SimpleClientHttpRequestFactory buildFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(60_000);
        return f;
    }

    /**
     * Returns PDF bytes if the pdf-service is reachable, otherwise returns the HTML
     * as UTF-8 bytes so the frontend can open it in a new tab for printing.
     */
    public PdfOrHtmlResult export(Long simulationId) {
        SimulationResult r = resultRepo.findById(simulationId)
                .orElseThrow(() -> new EntityNotFoundException("Simulation not found: " + simulationId));

        String html = buildHtml(r);
        String filename = "simulation-" + simulationId + ".pdf";

        try {
            String url = appProperties.getPdfServiceUrl() + "/pdf/api/render";
            byte[] pdfBytes = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("html", html, "filename", filename))
                    .retrieve()
                    .body(byte[].class);

            if (pdfBytes != null && pdfBytes.length > 0) {
                return new PdfOrHtmlResult(pdfBytes, "application/pdf", filename);
            }
        } catch (RestClientException ex) {
            log.warn("pdf-service unavailable for simulation {}: {} — returning HTML", simulationId, ex.getMessage());
        }

        return new PdfOrHtmlResult(html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/html;charset=UTF-8", "simulation-" + simulationId + ".html");
    }

    public record PdfOrHtmlResult(byte[] bytes, String contentType, String filename) {}

    // ── HTML builder ──────────────────────────────────────────────────────────

    private String buildHtml(SimulationResult r) {
        String curr = r.getLocalCurrency() != null ? r.getLocalCurrency() : "";
        String date = r.getSimulatedAt() != null ? r.getSimulatedAt().format(FMT) : "";

        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                <meta charset="UTF-8"/>
                <title>Simulation de paie #%d</title>
                <style>
                  body { font-family: Arial, sans-serif; font-size: 12px; color: #1a1a2e; margin: 0; padding: 24px; }
                  h1   { font-size: 18px; margin: 0 0 4px; }
                  .sub { font-size: 11px; color: #666; margin-bottom: 20px; }
                  table { width: 100%%; border-collapse: collapse; margin-bottom: 20px; }
                  th, td { padding: 8px 12px; border: 1px solid #e0e0e0; text-align: left; }
                  th   { background: #f0f0f8; font-weight: 600; }
                  .strate-s1 { background: #eef2ff; }
                  .strate-s2 { background: #e8eeff; }
                  .strate-s3 { background: #dde4ff; }
                  .strate-s4 { background: #d4dbff; }
                  .strate-s5 { background: #4f46e5; color: #fff; font-weight: 700; font-size: 13px; }
                  .fx-row td { background: #f9f9f9; font-size: 11px; }
                  .num { text-align: right; font-variant-numeric: tabular-nums; }
                  .ratio-box { background: #4f46e5; color: #fff; padding: 12px 16px; border-radius: 6px;
                               display: inline-block; font-size: 14px; font-weight: 700; margin-bottom: 12px; }
                  .meta { font-size: 11px; color: #555; margin-bottom: 16px; display: flex; gap: 16px; flex-wrap: wrap; }
                  .meta span { background: #f0f0f8; padding: 3px 8px; border-radius: 4px; }
                </style>
                </head>
                <body>
                <h1>Simulateur de paie DAF360°</h1>
                <div class="sub">Simulation #%d · %s · Généré le %s</div>

                %s

                <table>
                  <thead><tr><th>Strate</th><th>Désignation</th><th class="num">Montant %s</th></tr></thead>
                  <tbody>
                    <tr class="strate-s1"><td>S1</td><td>Net versé</td><td class="num">%s</td></tr>
                    <tr class="strate-s2"><td>S2</td><td>Net imposable (base IRPP)</td><td class="num">%s</td></tr>
                    <tr class="strate-s3"><td>S3</td><td>Brut base taxable</td><td class="num">%s</td></tr>
                    <tr class="strate-s4"><td>S4</td><td>Brut total (+ avantages exonérés)</td><td class="num">%s</td></tr>
                    <tr class="strate-s5"><td>S5</td><td>Coût chargé total</td><td class="num">%s</td></tr>
                  </tbody>
                </table>

                %s

                <table>
                  <thead><tr><th colspan="2">Détail du calcul</th></tr></thead>
                  <tbody>
                    <tr><td>IRPP</td><td class="num">%s %s</td></tr>
                    <tr><td>Charges salariales (CNSS + CSS)</td><td class="num">%s %s</td></tr>
                    <tr><td>Charges patronales</td><td class="num">%s %s</td></tr>
                    <tr><td>Type de contrat</td><td>%s</td></tr>
                    <tr><td>Itérations convergence</td><td>%d</td></tr>
                    <tr><td>Jeu de paramètres</td><td>#%d</td></tr>
                  </tbody>
                </table>
                </body>
                </html>
                """.formatted(
                r.getId(),
                r.getId(), curr, date,
                buildMetaHtml(r),
                curr,
                fmt(r.getInputNet()), fmt(r.getNetTaxable()), fmt(r.getGross()),
                fmt(r.getGrossWithBenefits() != null ? r.getGrossWithBenefits() : r.getGross()),
                fmt(r.getLoadedCost()),
                buildFxHtml(r, curr),
                fmt(r.getIrppAmount()), curr,
                fmt(r.getEmployeeCharges()), curr,
                fmt(r.getEmployerCharges()), curr,
                r.getContractType(),
                r.getIterationsUsed(),
                r.getParameterSetId()
        );
    }

    private String buildMetaHtml(SimulationResult r) {
        if (r.getCandidateLabel() == null && r.getPoste() == null
                && r.getGrade() == null && r.getDiscipline() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<div class=\"meta\">");
        if (r.getCandidateLabel() != null) sb.append("<span>").append(escape(r.getCandidateLabel())).append("</span>");
        if (r.getPoste()          != null) sb.append("<span>Poste : ").append(escape(r.getPoste())).append("</span>");
        if (r.getGrade()          != null) sb.append("<span>Grade : ").append(escape(r.getGrade())).append("</span>");
        if (r.getDiscipline()     != null) sb.append("<span>Discipline : ").append(escape(r.getDiscipline())).append("</span>");
        sb.append("</div>");
        return sb.toString();
    }

    private String buildFxHtml(SimulationResult r, String curr) {
        if (r.getLoadedCostEur() == null && r.getLoadedCostChf() == null) return "";
        StringBuilder sb = new StringBuilder("<table><tbody>");
        if (r.getLoadedCostEur() != null) {
            sb.append("<tr class=\"fx-row\"><td>Coût chargé en EUR</td><td class=\"num\">%s EUR</td><td>(1 %s = %s EUR)</td></tr>"
                    .formatted(fmt(r.getLoadedCostEur()), curr, fmtRate(r.getFxRateEur())));
        }
        if (r.getLoadedCostChf() != null) {
            sb.append("<tr class=\"fx-row\"><td>Coût chargé en CHF</td><td class=\"num\">%s CHF</td><td>(1 %s = %s CHF)</td></tr>"
                    .formatted(fmt(r.getLoadedCostChf()), curr, fmtRate(r.getFxRateChf())));
        }
        if (r.getLoadedCostUsd() != null) {
            sb.append("<tr class=\"fx-row\"><td>Coût chargé en USD</td><td class=\"num\">%s USD</td><td>(1 %s = %s USD)</td></tr>"
                    .formatted(fmt(r.getLoadedCostUsd()), curr, fmtRate(r.getFxRateUsd())));
        }
        if (r.getCostNetRatio() != null) {
            sb.append("<tr><td><strong>Ratio coût / net</strong></td><td colspan=\"2\"><strong>× %s</strong></td></tr>"
                    .formatted(r.getCostNetRatio().setScale(2, RoundingMode.HALF_UP)));
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "—";
        return String.format("%,.2f", v);
    }

    private static String fmtRate(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
