package com.daf360.payroll.modules.payroll.service;

import com.daf360.payroll.common.export.ExportFile;
import com.daf360.payroll.common.export.ExportLang;
import com.daf360.payroll.common.export.XlsxExport;
import com.daf360.payroll.common.export.XlsxExport.SheetData;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PaysScopeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Export Excel de la liste des collaborateurs de {@code /payroll/engine-results}.
 * <p>
 * L'annuaire vient du service RH ({@code /api/hr/profiles/employees}), que le service paie ne
 * lit pas : le front envoie les lignes elles-mêmes — tout le périmètre filtré (recherche,
 * filtres, tri ; toutes les pages), libellés déjà traduits. Le serveur y ajoute, pour chaque
 * collaborateur, les tuiles de sa page historique ({@code /payroll/engine-results/:id}) lues en
 * base paie : brut cumulé, retenues cumulées (cotisations + impôt) et leur part du brut, net
 * cumulé, moyennes mensuelles, mois calculés et mois manquants.
 * <p>
 * Même règle que la page : un seul résultat par mois, le plus récent (plus grand id — le
 * moteur ajoute un résultat à chaque lancement). Période cumulée : celle du filtre « Période »
 * de la liste quand il est posé ; sinon, comme la page historique, l'année la plus récente du
 * collaborateur (mois manquants comptés jusqu'au mois en cours).
 */
@Service
@RequiredArgsConstructor
public class EmployeeDirectoryExportService {

    /** Garde-fou : la liste filtrée côté front est plafonnée à 2000 collaborateurs. */
    private static final int MAX_ROWS = 10_000;
    /** Lot de la requête {@code IN} (SQL Server : 2100 paramètres au plus). */
    private static final int ID_BATCH = 1000;

    private final PayrollResultRepository resultRepo;
    private final PayrollCountryRepository countryRepo;
    private final UserContextService userContext;

    public record Row(Long userId, String fullName, String employeeId, String department, String contract,
                      String pays, String status) {}

    /** {@code from} / {@code to} : bornes {@code YYYY-MM-DD} du filtre « Période » (au mois près), ou null. */
    public record Request(List<Row> rows, String from, String to) {}

    @Transactional(readOnly = true)
    public ExportFile exportXlsx(Request req, ExportLang lang) {
        List<Row> source = req != null && req.rows() != null ? req.rows() : List.of();
        if (source.size() > MAX_ROWS) source = source.subList(0, MAX_ROWS);

        Map<Long, List<PayrollResult>> byEmployee = latestPerMonth(source);
        int[] range = range(req);
        LocalDate today = LocalDate.now();
        Map<Long, String> currencies = new HashMap<>();

        List<List<Object>> rows = new ArrayList<>(source.size());
        for (Row r : source) {
            List<PayrollResult> results = r.userId() != null ? byEmployee.getOrDefault(r.userId(), List.of()) : List.of();
            List<Object> line = new ArrayList<>(Arrays.<Object>asList(
                    r.fullName(), r.employeeId(), r.department(), r.contract(), r.pays(), r.status()));
            line.addAll(kpis(results, range, today, currencies, lang));
            rows.add(line);
        }

        List<String> headers = List.of(
                lang.pick("Collaborateur", "Employee"),
                lang.pick("Matricule", "Employee ID"),
                lang.pick("Département", "Department"),
                lang.pick("Contrat", "Contract"),
                lang.pick("Pays", "Country"),
                lang.pick("Statut", "Status"),
                lang.pick("Période cumulée", "Cumulated period"),
                lang.pick("Devise", "Currency"),
                lang.pick("Brut cumulé", "Cumulated gross"),
                lang.pick("Retenues cumulées (cotisations + impôt)", "Cumulated deductions (contributions + tax)"),
                lang.pick("Retenues (% du brut)", "Deductions (% of gross)"),
                lang.pick("Net cumulé", "Cumulated net"),
                lang.pick("Brut moyen / mois", "Average gross / month"),
                lang.pick("Net moyen / mois", "Average net / month"),
                lang.pick("Mois calculés", "Months calculated"),
                lang.pick("Mois manquants", "Missing months"));

        byte[] bytes = XlsxExport.write(List.of(new SheetData(lang.pick("Collaborateurs", "Employees"), headers, rows)));
        return ExportFile.xlsx(lang.pick("Collaborateurs_paie_", "Payroll_employees_") + today + ".xlsx", bytes);
    }

    /**
     * Colonnes « tuiles » d'un collaborateur. {@code range} = [début, fin] en clés AAAAMM (filtre
     * Période), ou null : année la plus récente du collaborateur.
     */
    private List<Object> kpis(List<PayrollResult> all, int[] range, LocalDate today,
                              Map<Long, String> currencies, ExportLang lang) {
        int nowKey = today.getYear() * 100 + today.getMonthValue();
        String periodLabel;
        List<Integer> expected;   // mois attendus (clés AAAAMM), jusqu'au mois en cours
        int monthsTotal;          // dénominateur « x / N »
        List<PayrollResult> rows;
        if (range != null) {
            rows = all.stream().filter(r -> between(key(r), range)).toList();
            List<Integer> months = monthsBetween(range[0], range[1]);
            monthsTotal = months.size();
            expected = months.stream().filter(k -> k <= nowKey).toList();
            periodLabel = rangeLabel(range, lang);
        } else {
            if (all.isEmpty()) {
                return Arrays.asList(null, null, null, null, null, null, null, null, null, null);
            }
            int year = all.stream().mapToInt(PayrollResult::getPeriodYear).max().getAsInt();
            rows = all.stream().filter(r -> r.getPeriodYear() == year).toList();
            int upTo = year < today.getYear() ? 12 : year == today.getYear() ? today.getMonthValue() : 0;
            expected = new ArrayList<>();
            for (int m = 1; m <= upTo; m++) expected.add(year * 100 + m);
            monthsTotal = 12;
            periodLabel = String.valueOf(year);
        }

        Set<Integer> done = rows.stream().map(EmployeeDirectoryExportService::key).collect(Collectors.toSet());
        String missing = expected.stream().filter(k -> !done.contains(k))
                .map(k -> monthName(k, lang, range != null && range[0] / 100 != range[1] / 100))
                .collect(Collectors.joining(", "));
        String monthsCell = rows.size() + " / " + monthsTotal;
        if (rows.isEmpty()) {
            return Arrays.asList(periodLabel, null, null, null, null, null, null, null, monthsCell, missing);
        }

        BigDecimal gross = BigDecimal.ZERO, net = BigDecimal.ZERO, deductions = BigDecimal.ZERO;
        for (PayrollResult r : rows) {
            gross = gross.add(grossOf(r));
            net = net.add(nz(netOf(r)));
            // Retenues salariales réelles : S3 (CNSS salariale) + IRPP — comme la page historique.
            deductions = deductions.add(nz(r.getStrate3())).add(nz(irppOf(r)));
        }
        BigDecimal n = BigDecimal.valueOf(rows.size());
        BigDecimal pct = gross.signum() > 0
                ? deductions.multiply(BigDecimal.valueOf(100)).divide(gross, 1, RoundingMode.HALF_UP)
                : null;
        PayrollResult last = rows.stream().max(Comparator.comparingInt(EmployeeDirectoryExportService::key)).get();
        String currency = currencies.computeIfAbsent(last.getPaysId(), id -> id == null ? null
                : countryRepo.findByPaysId(id).map(PayrollCountry::getCurrencyCode).orElse(null));

        return Arrays.asList(periodLabel, currency,
                scale2(gross), scale2(deductions), pct, scale2(net),
                gross.divide(n, 2, RoundingMode.HALF_UP), net.divide(n, 2, RoundingMode.HALF_UP),
                monthsCell, missing);
    }

    /** Résultats des collaborateurs de la liste, dans le périmètre pays de l'utilisateur,
     *  un par mois (le plus récent). */
    private Map<Long, List<PayrollResult>> latestPerMonth(List<Row> source) {
        List<Long> ids = source.stream().map(Row::userId).filter(Objects::nonNull).distinct().toList();
        boolean superAdmin = isSuperAdmin();
        PaysScopeContext.PaysScope scope = superAdmin ? null : userContext.getCurrentUserPaysScope();
        Map<Long, Map<Integer, PayrollResult>> latest = new HashMap<>();
        for (int i = 0; i < ids.size(); i += ID_BATCH) {
            for (PayrollResult r : resultRepo.findByEmployeeIdIn(ids.subList(i, Math.min(ids.size(), i + ID_BATCH)))) {
                if (!superAdmin && (r.getPaysId() == null || scope == null || !scope.allows(r.getPaysId()))) continue;
                latest.computeIfAbsent(r.getEmployeeId(), k -> new HashMap<>())
                        .merge(key(r), r, (a, b) -> a.getId() >= b.getId() ? a : b);
            }
        }
        Map<Long, List<PayrollResult>> out = new HashMap<>();
        latest.forEach((emp, byMonth) -> out.put(emp, new ArrayList<>(byMonth.values())));
        return out;
    }

    /** Filtre « Période » en clés AAAAMM ; une seule borne : l'autre retombe sur janvier de la même
     *  année (début) ou le mois en cours (fin). Null sans filtre. */
    private static int[] range(Request req) {
        LocalDate from = parse(req != null ? req.from() : null);
        LocalDate to = parse(req != null ? req.to() : null);
        if (from == null && to == null) return null;
        if (to == null) to = LocalDate.now().isBefore(from) ? from : LocalDate.now();
        if (from == null) from = LocalDate.of(to.getYear(), 1, 1);
        int a = from.getYear() * 100 + from.getMonthValue(), b = to.getYear() * 100 + to.getMonthValue();
        return a <= b ? new int[] {a, b} : new int[] {b, a};
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim().substring(0, Math.min(10, s.trim().length())));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Integer> monthsBetween(int fromKey, int toKey) {
        List<Integer> out = new ArrayList<>();
        int y = fromKey / 100, m = fromKey % 100;
        while (y * 100 + m <= toKey) {
            out.add(y * 100 + m);
            if (++m > 12) { m = 1; y++; }
        }
        return out;
    }

    private static String rangeLabel(int[] range, ExportLang lang) {
        String a = monthName(range[0], lang, true), b = monthName(range[1], lang, true);
        return a.equals(b) ? a : a + " – " + b;
    }

    /** « Février » ; avec l'année (« Février 2026 ») quand la période couvre plusieurs années. */
    private static String monthName(int key, ExportLang lang, boolean withYear) {
        String name = Month.of(key % 100).getDisplayName(TextStyle.FULL, lang.locale());
        name = name.substring(0, 1).toUpperCase(lang.locale()) + name.substring(1);
        return withYear ? name + " " + key / 100 : name;
    }

    private static boolean between(int key, int[] range) {
        return key >= range[0] && key <= range[1];
    }

    private static int key(PayrollResult r) {
        return r.getPeriodYear() * 100 + r.getPeriodMonth();
    }

    /** Mêmes lectures que l'export de l'historique ({@code PayrollResultExportService}). */
    private static BigDecimal grossOf(PayrollResult r) {
        return r.getAggregateGross() != null ? r.getAggregateGross() : nz(r.getStrate1()).add(nz(r.getStrate2()));
    }

    private static BigDecimal netOf(PayrollResult r) {
        return r.getAggregateNet() != null ? r.getAggregateNet() : r.getStrate5();
    }

    private static BigDecimal irppOf(PayrollResult r) {
        return r.getAggregateIrpp() != null ? r.getAggregateIrpp() : r.getStrate4();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "PAYROLL_SUPER_ADMIN".equals(a.getAuthority()));
    }
}
