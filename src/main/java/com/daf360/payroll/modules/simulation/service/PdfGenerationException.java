package com.daf360.payroll.modules.simulation.service;

/**
 * Le pdf-service n'a pas produit de PDF (injoignable ou réponse vide) — même rôle que
 * {@code PdfGenerationException} côté facturation : remonté en 503 par
 * {@code GlobalExceptionHandler}, le front affiche le message au lieu d'un faux fichier.
 */
public class PdfGenerationException extends RuntimeException {

    public PdfGenerationException(String message) {
        super(message);
    }

    public PdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
