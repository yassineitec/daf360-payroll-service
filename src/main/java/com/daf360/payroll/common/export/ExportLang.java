package com.daf360.payroll.common.export;

import java.util.Locale;
import java.util.Map;

/**
 * Langue des libellés d'un export — copie de {@code com.daf360.facturation.common.export.ExportLang}.
 * Le backend n'a pas de fichiers de traduction : le front envoie sa langue courante
 * ({@code ?lang=fr|en}) et chaque service d'export porte ses propres libellés FR/EN, repris de
 * {@code public/i18n/*.json} côté front. Toute autre valeur retombe sur le français.
 */
public enum ExportLang {
    FR(Locale.FRENCH),
    EN(Locale.UK);

    private final Locale locale;

    ExportLang(Locale locale) {
        this.locale = locale;
    }

    public Locale locale() {
        return locale;
    }

    public static ExportLang of(String code) {
        return code != null && code.trim().toLowerCase(Locale.ROOT).startsWith("en") ? EN : FR;
    }

    public String pick(String fr, String en) {
        return this == EN ? en : fr;
    }

    /** Libellé d'un code depuis une table {@code code → {fr, en}} ; code inconnu → le code tel quel,
     *  comme le front. */
    public String label(Map<String, String[]> labels, String code) {
        String[] l = code != null ? labels.get(code) : null;
        return l != null ? pick(l[0], l[1]) : (code != null ? code : "—");
    }
}
