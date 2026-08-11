package com.daf360.payroll.engine;

import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Unified two-tier topological evaluation engine for the DAF360° payroll simulator.
 *
 * <h3>Phase 1 — Charges</h3>
 * Social charge lines for the requested contract type are evaluated in topological order.
 * Dependencies between charges are inferred automatically from {@code formula_ee} /
 * {@code formula_er} expressions: if charge B's formula references {@code CNSS_EE},
 * the engine ensures charge {@code CNSS} is evaluated before charge B.
 * Rate-based lines (no formula) have no code-based dependencies and are always
 * evaluated before formula-based ones unless their {@code eval_order} says otherwise.
 *
 * <h3>Phase 1.5 — Aggregates</h3>
 * After all charge lines are evaluated:
 * <pre>
 *   CHARGES_EE = Σ {CODE}_EE
 *   CHARGES_ER = Σ {CODE}_ER
 * </pre>
 * These aggregates are added to the shared context so rubrique formulas can reference them.
 *
 * <h3>Phase 2 — Rubriques</h3>
 * Rubrique lines are evaluated in topological order derived from {@code display_order} and
 * inter-rubrique formula references.  A {@code FORMULE} rubrique may reference the results
 * of earlier rubriques by their sanitized code (e.g. {@code PRIME_ANCIENNETE}).
 *
 * <h3>Cycle detection</h3>
 * Both phases use Kahn's algorithm.  A {@link CyclicDependencyException} is thrown before
 * any evaluation begins if a cycle is detected, making the failure fast and deterministic.
 *
 * <h3>Design constraints (by intent)</h3>
 * <ul>
 *   <li>A charge formula may reference {@code BRUT} and the results of prior charges
 *       ({@code {CODE}_EE}, {@code {CODE}_ER}).</li>
 *   <li>A charge formula <em>cannot</em> reference {@code CHARGES_EE}/{@code CHARGES_ER}
 *       (those aggregates are not yet defined at charge-evaluation time) or rubrique results
 *       (rubriques run after charges).</li>
 *   <li>A rubrique formula may reference {@code BRUT}, any charge result, the aggregate
 *       variables, and any rubrique result computed earlier in the topological order.</li>
 * </ul>
 */
@Service
public class TopologicalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TopologicalEvaluator.class);
    private static final int SCALE = 4;
    private static final int STANDARD_WORKING_DAYS = 22;
    private static final Pattern VAR_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]+");

    // ─────────────────────────────────────────────────────────────────────────
    //  Public result types
    // ─────────────────────────────────────────────────────────────────────────

    /** A rubrique paired with its computed amount, in the order it was evaluated. */
    public record EvaluatedRubrique(PayrollRubrique rubrique, BigDecimal amount) {}

    /**
     * Complete result of one unified evaluation pass.
     *
     * @param context            flat variable map: {@code BRUT}, charge lines, aggregates,
     *                           rubrique results — all variables available after evaluation
     * @param totalChargesEE     sum of all employee-side charge amounts (≡ {@code CHARGES_EE})
     * @param totalChargesER     sum of all employer-side charge amounts (≡ {@code CHARGES_ER})
     * @param evaluatedRubriques rubriques in the order they were evaluated, each with its amount
     */
    public record EvaluationResult(
            Map<String, BigDecimal> context,
            BigDecimal totalChargesEE,
            BigDecimal totalChargesER,
            List<EvaluatedRubrique> evaluatedRubriques
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    private final FormulaEvaluatorService formulaEval;

    public TopologicalEvaluator(FormulaEvaluatorService formulaEval) {
        this.formulaEval = formulaEval;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Evaluate all social charge lines and rubrique lines for the given gross salary.
     *
     * @param gross          gross salary ({@code BRUT} — the root of all calculations)
     * @param rates          all social charge rate rows for this parameter set
     * @param contractType   contract type used to filter both charges and rubriques
     * @param rubriques      all rubrique rows (active-only or full — inactive are filtered here)
     * @param joursTravailes working days in the month (for {@code FIXE_JOURNALIER} rubriques)
     * @return evaluation result containing the full variable context, charge totals, and rubrique amounts
     * @throws CyclicDependencyException if a dependency cycle is detected in either phase
     */
    public EvaluationResult evaluate(BigDecimal gross,
                                     List<SocialChargeRate> rates,
                                     String contractType,
                                     List<PayrollRubrique> rubriques,
                                     int joursTravailes) {

        Map<String, BigDecimal> ctx = new LinkedHashMap<>();
        ctx.put("BRUT", gross);

        // ── Phase 1: Social charges ───────────────────────────────────────────
        List<SocialChargeRate> filteredRates = rates.stream()
                .filter(r -> contractType.equals(r.getContractType()))
                .sorted(Comparator.comparingInt(r -> r.getEvalOrder() != null ? r.getEvalOrder() : 0))
                .toList();

        // Collect all output variable names for dependency extraction
        Set<String> chargeOutputCodes = new LinkedHashSet<>();
        for (SocialChargeRate r : filteredRates) {
            String key = sanitize(r.getChargeCode());
            chargeOutputCodes.add(key + "_EE");
            chargeOutputCodes.add(key + "_ER");
        }

        // Build the dependency graph for charges.
        // Node key = sanitized charge code (both _EE and _ER share the same node).
        List<String> chargeNodes = filteredRates.stream()
                .map(r -> sanitize(r.getChargeCode()))
                .distinct()
                .toList();

        Map<String, Set<String>> chargeDeps = new LinkedHashMap<>();
        for (SocialChargeRate r : filteredRates) {
            String node = sanitize(r.getChargeCode());
            Set<String> deps = new LinkedHashSet<>();
            collectChargeDeps(r.getFormulaEe(), chargeOutputCodes, node, deps);
            collectChargeDeps(r.getFormulaEr(), chargeOutputCodes, node, deps);
            chargeDeps.merge(node, deps, (existing, incoming) -> { existing.addAll(incoming); return existing; });
        }
        chargeNodes.forEach(n -> chargeDeps.putIfAbsent(n, new LinkedHashSet<>()));

        List<String> sortedChargeNodes = topologicalSort(chargeNodes, chargeDeps);

        // Build lookup: sanitized chargeCode → entity
        Map<String, SocialChargeRate> rateByNode = new LinkedHashMap<>();
        filteredRates.forEach(r -> rateByNode.put(sanitize(r.getChargeCode()), r));

        BigDecimal totalEE = BigDecimal.ZERO;
        BigDecimal totalER = BigDecimal.ZERO;

        for (String node : sortedChargeNodes) {
            SocialChargeRate r = rateByNode.get(node);
            if (r == null) continue;

            BigDecimal ee = hasFormula(r.getFormulaEe())
                    ? formulaEval.evaluate(r.getFormulaEe(), ctx)
                    : applyRate(gross, r.getEmployeeRate(), r.getCapAmount());

            BigDecimal er = hasFormula(r.getFormulaEr())
                    ? formulaEval.evaluate(r.getFormulaEr(), ctx)
                    : applyRate(gross, r.getEmployerRate(), r.getCapAmount());

            ctx.put(node + "_EE", ee);
            ctx.put(node + "_ER", er);
            totalEE = totalEE.add(ee);
            totalER = totalER.add(er);
        }

        // Phase 1.5: aggregate charge variables (available for rubrique formulas)
        ctx.put("CHARGES_EE", totalEE);
        ctx.put("CHARGES_ER", totalER);

        // ── Phase 2: Rubriques ────────────────────────────────────────────────
        List<PayrollRubrique> filteredRubriques = rubriques.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .filter(r -> r.getContractTypes() == null
                        || r.getContractTypes().isBlank()
                        || Arrays.asList(r.getContractTypes().split(",")).contains(contractType))
                .sorted(Comparator.comparingInt(r -> r.getDisplayOrder() != null ? r.getDisplayOrder() : 0))
                .toList();

        // Known rubrique output codes (for inter-rubrique dependency extraction)
        Set<String> rubriqueCodes = filteredRubriques.stream()
                .filter(r -> r.getCode() != null && !r.getCode().isBlank())
                .map(r -> sanitize(r.getCode()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> rubriqueNodes = filteredRubriques.stream()
                .filter(r -> r.getCode() != null && !r.getCode().isBlank())
                .map(r -> sanitize(r.getCode()))
                .distinct()
                .toList();

        Map<String, Set<String>> rubriqueDeps = new LinkedHashMap<>();
        for (PayrollRubrique r : filteredRubriques) {
            if (r.getCode() == null || r.getCode().isBlank()) continue;
            String node = sanitize(r.getCode());
            Set<String> deps = new LinkedHashSet<>();
            if ("FORMULE".equals(r.getCalcMode()) && r.getFormulaExpression() != null) {
                Set<String> refs = extractRefs(r.getFormulaExpression(), rubriqueCodes);
                refs.remove(node); // avoid self-reference
                deps.addAll(refs);
            }
            rubriqueDeps.put(node, deps);
        }
        rubriqueNodes.forEach(n -> rubriqueDeps.putIfAbsent(n, new LinkedHashSet<>()));

        List<String> sortedRubriqueNodes = topologicalSort(rubriqueNodes, rubriqueDeps);

        // Build lookup: sanitized code → entity
        Map<String, PayrollRubrique> rubriqueByNode = new LinkedHashMap<>();
        filteredRubriques.stream()
                .filter(r -> r.getCode() != null && !r.getCode().isBlank())
                .forEach(r -> rubriqueByNode.put(sanitize(r.getCode()), r));

        final BigDecimal totalCharges = totalEE.add(totalER);
        List<EvaluatedRubrique> evaluatedRubriques = new ArrayList<>(filteredRubriques.size());

        // Evaluate named rubriques in topological order
        for (String node : sortedRubriqueNodes) {
            PayrollRubrique r = rubriqueByNode.get(node);
            if (r == null) continue;
            BigDecimal amt = computeRubriqueAmt(r, gross, totalCharges, joursTravailes, ctx);
            ctx.put(node, amt);
            evaluatedRubriques.add(new EvaluatedRubrique(r, amt));
        }

        // Evaluate unnamed rubriques (legacy rows without a code) in display_order order
        filteredRubriques.stream()
                .filter(r -> r.getCode() == null || r.getCode().isBlank())
                .forEach(r -> {
                    BigDecimal amt = computeRubriqueAmt(r, gross, totalCharges, joursTravailes, ctx);
                    evaluatedRubriques.add(new EvaluatedRubrique(r, amt));
                });

        return new EvaluationResult(ctx, totalEE, totalER, Collections.unmodifiableList(evaluatedRubriques));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Topological sort — Kahn's algorithm
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code nodes} in topological order.
     * {@code deps.get(A)} is the set of node keys that A depends on (must run before A).
     * The initial ordering of {@code nodes} breaks ties deterministically.
     *
     * @throws CyclicDependencyException if the graph contains a cycle
     */
    private List<String> topologicalSort(List<String> nodes, Map<String, Set<String>> deps) {
        if (nodes.isEmpty()) return List.of();

        // in-degree[A] = number of nodes in our set that A explicitly depends on
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String n : nodes) inDegree.put(n, 0);

        for (String n : nodes) {
            for (String dep : deps.getOrDefault(n, Set.of())) {
                if (inDegree.containsKey(dep)) {
                    // dep must precede n → n's in-degree increases
                    inDegree.merge(n, 1, Integer::sum);
                }
            }
        }

        // Start with all nodes that have no known prerequisites
        Queue<String> ready = new ArrayDeque<>();
        for (String n : nodes) {
            if (inDegree.get(n) == 0) ready.add(n);
        }

        List<String> sorted = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            sorted.add(n);
            // Reduce the in-degree of all nodes that depend on n
            for (String m : nodes) {
                if (deps.getOrDefault(m, Set.of()).contains(n)) {
                    int remaining = inDegree.merge(m, -1, Integer::sum);
                    if (remaining == 0) ready.add(m);
                }
            }
        }

        if (sorted.size() < nodes.size()) {
            Set<String> processed = new HashSet<>(sorted);
            List<String> cycle = nodes.stream()
                    .filter(n -> !processed.contains(n))
                    .toList();
            log.error("Cycle detected in payroll formula dependencies: {}", cycle);
            throw new CyclicDependencyException(cycle);
        }

        return sorted;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Rubrique computation (mirrors PayrollSimulatorService.computeRubriqueAmount)
    // ─────────────────────────────────────────────────────────────────────────

    private BigDecimal computeRubriqueAmt(PayrollRubrique r,
                                           BigDecimal gross,
                                           BigDecimal totalBaseCharges,
                                           int joursTravailes,
                                           Map<String, BigDecimal> ctx) {
        return switch (r.getCalcMode()) {
            case "FIXE_MENSUEL" ->
                    r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
            case "FIXE_JOURNALIER" ->
                    r.getAmount() != null
                            ? r.getAmount()
                              .multiply(BigDecimal.valueOf(joursTravailes))
                              .divide(BigDecimal.valueOf(STANDARD_WORKING_DAYS), SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
            case "POURCENTAGE_BRUT" ->
                    r.getRate() != null
                            ? gross.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
            case "POURCENTAGE_CHARGES" ->
                    r.getRate() != null
                            ? totalBaseCharges.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
            case "POURCENTAGE_PLAFONNE" -> {
                if (r.getRate() == null) yield BigDecimal.ZERO;
                BigDecimal effectiveBase = r.getCapAmount() != null ? gross.min(r.getCapAmount()) : gross;
                yield effectiveBase.multiply(r.getRate()).setScale(SCALE, RoundingMode.HALF_UP);
            }
            case "FORMULE" -> formulaEval.evaluate(r.getFormulaExpression(), ctx);
            default -> {
                log.warn("Unknown rubrique calcMode '{}' for code='{}', returning zero.", r.getCalcMode(), r.getCode());
                yield BigDecimal.ZERO;
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Derives the charge-node dependency of a formula for the given self-node and
     * adds it to {@code deps}.
     * References like {@code CNSS_EE} or {@code TFP_ER} map back to nodes {@code CNSS}
     * and {@code TFP} respectively.
     */
    private void collectChargeDeps(String formula,
                                    Set<String> knownOutputCodes,
                                    String selfNode,
                                    Set<String> deps) {
        if (!hasFormula(formula)) return;
        for (String ref : extractRefs(formula, knownOutputCodes)) {
            String depNode = stripSuffix(ref);
            if (!depNode.equals(selfNode)) deps.add(depNode);
        }
    }

    /** Strips {@code _EE} or {@code _ER} suffix to get the charge node key. */
    private String stripSuffix(String varName) {
        if (varName.endsWith("_EE")) return varName.substring(0, varName.length() - 3);
        if (varName.endsWith("_ER")) return varName.substring(0, varName.length() - 3);
        return varName;
    }

    /**
     * Extracts all uppercase identifiers in {@code formula} that are members of
     * {@code knownCodes}.
     */
    private Set<String> extractRefs(String formula, Set<String> knownCodes) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = VAR_PATTERN.matcher(formula.toUpperCase());
        while (m.find()) {
            String v = m.group();
            if (knownCodes.contains(v)) refs.add(v);
        }
        return refs;
    }

    /**
     * Rate-based charge computation: {@code min(base, cap) × rate}.
     * Consistent with the legacy {@code buildChargeVariables} logic.
     */
    private BigDecimal applyRate(BigDecimal base, BigDecimal rate, BigDecimal cap) {
        if (rate == null) return BigDecimal.ZERO;
        BigDecimal effectiveBase = (cap != null) ? base.min(cap) : base;
        return effectiveBase.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private boolean hasFormula(String formula) {
        return formula != null && !formula.isBlank();
    }

    /**
     * Normalises a charge/rubrique code to a valid exp4j variable name:
     * uppercase letters, digits and underscores only.
     */
    public static String sanitize(String code) {
        if (code == null || code.isBlank()) return "UNKNOWN";
        return code.toUpperCase().replaceAll("[^A-Z0-9]", "_");
    }
}
