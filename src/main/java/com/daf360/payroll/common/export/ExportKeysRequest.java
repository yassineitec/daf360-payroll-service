package com.daf360.payroll.common.export;

import java.util.List;

/** Corps des exports « tableau » : les clés des lignes affichées, dans l'ordre de l'écran ;
 * corps absent ou {@code ids} null = toute la source. Voir {@link ExportRows#select}.
 * Clés texte (codes de rubrique) — l'équivalent facturation porte des ids numériques. */
public record ExportKeysRequest(List<String> ids) {

    public static List<String> idsOf(ExportKeysRequest req) {
        return req != null ? req.ids() : null;
    }
}
