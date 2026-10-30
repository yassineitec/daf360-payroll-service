package com.daf360.payroll.common.export;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Fichier d'export prêt à renvoyer : nom proposé au téléchargement, type et contenu.
 *  Même réponse « pièce jointe » que {@code XlsxExport.attachment} de la facturation,
 *  étendue au PDF. */
public record ExportFile(String filename, MediaType contentType, byte[] content) {

    public static ExportFile xlsx(String filename, byte[] content) {
        return new ExportFile(filename, XlsxExport.XLSX_MEDIA_TYPE, content);
    }

    public static ExportFile pdf(String filename, byte[] content) {
        return new ExportFile(filename, MediaType.APPLICATION_PDF, content);
    }

    public ResponseEntity<byte[]> toResponse() {
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(content);
    }
}
