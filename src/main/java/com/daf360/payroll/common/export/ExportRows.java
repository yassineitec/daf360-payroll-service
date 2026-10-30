package com.daf360.payroll.common.export;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Sélection des lignes affichées à l'écran — même contrat que
 * {@code com.daf360.facturation.common.export.ExportRows} : le front envoie les ids des lignes
 * qu'il affiche (après recherche, filtres <b>et tri</b>) ; le serveur relit la source en base et
 * ne garde que ces lignes, <b>dans l'ordre des ids reçus</b>, ce qui reproduit l'écran sans
 * réimplémenter ses filtres en Java. Un id étranger à la source est ignoré.
 * {@code ids == null} : toute la source, dans son ordre naturel.
 */
public final class ExportRows {

    private ExportRows() {}

    public static <T, K> List<T> select(List<T> all, Collection<K> ids, Function<T, K> idOf) {
        if (ids == null) return all;
        Map<K, T> byId = new LinkedHashMap<>();
        for (T x : all) byId.putIfAbsent(idOf.apply(x), x);
        List<T> out = new ArrayList<>(ids.size());
        Set<K> seen = new HashSet<>();
        for (K id : ids) {
            T x = byId.get(id);
            if (x != null && seen.add(id)) out.add(x);
        }
        return out;
    }

    /** Nom de fichier sûr : tout ce qui n'est ni lettre ni chiffre devient « _ ». */
    public static String safeFilePart(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9-]+", "_");
    }
}
