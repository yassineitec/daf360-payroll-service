package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.engine.PayrollSimulatorService;
import com.daf360.payroll.engine.PayrollSimulatorService.PayrollResult;
import com.daf360.payroll.modules.employeeconfig.entity.EmployeePayrollConfig;
import com.daf360.payroll.modules.parameterset.entity.BenefitCatalogue;
import com.daf360.payroll.modules.parameterset.entity.ParameterSet;
import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.simulation.client.HrEmployeeClient.HrEmployeeDto;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfRow;
import com.daf360.payroll.modules.simulation.dto.EmployeeWhatIfRow.Figures;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * One employee of a cohort what-if, with exactly the engine call the individual simulator
 * ({@code IndividualSimulationService.compute}) makes for the same inputs — same parameter
 * set, contract, family status, benefits and working days — so a single employee here equals
 * {@code POST /simulations/individual} to the cent (B1).
 *
 * <ul>
 *   <li>Family status (DEC8): the HR file's married / children, as the individual simulator
 *       does for an employee.</li>
 *   <li>Benefits (DEC5): the employee's own selection when a payroll configuration exists
 *       (its JSON list; an empty or absent list = no benefit, as on the Rémunération screen);
 *       otherwise every benefit of the set, flagged {@code benefitsAssumed}.</li>
 *   <li>Current gross (DEC6): the configured gross when re-computing the net from it gives the
 *       HR net back within the tolerance; otherwise the gross computed from the HR net.</li>
 * </ul>
 */
@Component
public class CohortEmployeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PayrollSimulatorService simulator;
    private final ObjectMapper objectMapper;

    public CohortEmployeeCalculator(PayrollSimulatorService simulator, ObjectMapper objectMapper) {
        this.simulator = simulator;
        this.objectMapper = objectMapper;
    }

    /** The active parameter set of the country, loaded once per request. */
    public record EngineContext(ParameterSet ps, List<SocialChargeRate> rates, List<BenefitCatalogue> allBenefits,
                                List<PayrollRubrique> rubriques, int workingDays, BigDecimal grossTolerance) {}

    public enum ChangeType {
        PERCENT, AMOUNT;

        /** null/blank → PERCENT; legacy aliases PCT / ABSOLU accepted; anything else → 400. */
        public static ChangeType parse(String raw) {
            if (raw == null || raw.isBlank()) return PERCENT;
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "PERCENT", "PCT" -> PERCENT;
                case "AMOUNT", "ABSOLU" -> AMOUNT;
                default -> throw new IllegalArgumentException(
                        "modifierType inconnu : « " + raw + " ». Valeurs acceptées : PERCENT (ou PCT), AMOUNT (ou ABSOLU).");
            };
        }
    }

    public enum SalaryBase {
        NET, GROSS;

        public static SalaryBase parse(String raw) {
            if (raw == null || raw.isBlank()) return NET;
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "NET" -> NET;
                case "GROSS", "BRUT" -> GROSS;
                default -> throw new IllegalArgumentException(
                        "salaryBase inconnu : « " + raw + " ». Valeurs acceptées : NET, GROSS.");
            };
        }
    }

    /** The change requested (DEC3 bounds are checked by {@link #validate}). */
    public record Change(ChangeType type, BigDecimal value, SalaryBase base) {

        public static final BigDecimal MIN_PCT = BigDecimal.valueOf(-50);
        public static final BigDecimal MAX_PCT = BigDecimal.valueOf(200);

        public BigDecimal apply(BigDecimal baseAmount) {
            return switch (type) {
                case PERCENT -> baseAmount.multiply(BigDecimal.ONE.add(value.divide(HUNDRED)))
                        .setScale(4, RoundingMode.HALF_UP);
                case AMOUNT -> baseAmount.add(value).setScale(4, RoundingMode.HALF_UP);
            };
        }

        public void validate() {
            if (type == ChangeType.PERCENT && (value.compareTo(MIN_PCT) < 0 || value.compareTo(MAX_PCT) > 0)) {
                throw new IllegalArgumentException(
                        "Pourcentage hors bornes : il doit être compris entre −50 et +200.");
            }
        }
    }

    /** Thrown when a change would bring a salary to 0 or below (DEC3: rejected, never floored). */
    public static class NonPositiveSalaryException extends IllegalArgumentException {
        public NonPositiveSalaryException() {
            super("Le montant demandé rendrait au moins un salaire nul ou négatif : modification refusée.");
        }
    }

    /** True when the HR record has a usable net salary. */
    public static boolean hasSalary(HrEmployeeDto e) {
        return e.salaireNetRh() != null && e.salaireNetRh().signum() > 0;
    }

    public static String contractOf(HrEmployeeDto e) {
        return e.contractType() != null && !e.contractType().isBlank() ? e.contractType() : "CDI";
    }

    /**
     * Benefits the engine applies for this employee (DEC5), whether they were assumed (no
     * configuration), and whether the configuration selects none at all (its list is NULL or
     * empty — read as "no benefit", which the screen now flags; the reading itself is unchanged).
     */
    public record BenefitChoice(List<BenefitCatalogue> benefits, boolean assumed, List<String> codes,
                                boolean noneSelected) {}

    public BenefitChoice benefitsFor(EngineContext ctx, EmployeePayrollConfig cfg) {
        if (cfg == null) return new BenefitChoice(ctx.allBenefits(), true, null, false);
        List<String> codes = parseCodes(cfg.getSelectedBenefitCodes());
        return new BenefitChoice(ctx.allBenefits().stream()
                .filter(b -> codes.contains(b.getBenefitCode())).toList(), false, codes, codes.isEmpty());
    }

    public EmployeeWhatIfRow compute(EngineContext ctx, HrEmployeeDto e, EmployeePayrollConfig cfg, Change change) {
        String contract = contractOf(e);
        Boolean married = e.married();
        Integer children = e.married() != null ? (e.childrenCount() != null ? e.childrenCount() : 0) : null;
        BenefitChoice bc = benefitsFor(ctx, cfg);
        List<BenefitCatalogue> benefits = bc.benefits();
        BigDecimal net = e.salaireNetRh();

        // Current figures (DEC6)
        PayrollResult current = null;
        String grossSource = "COMPUTED";
        if (cfg != null && cfg.getCurrentGrossSalary() != null && cfg.getCurrentGrossSalary().signum() > 0) {
            PayrollResult fromGross = fromGross(ctx, cfg.getCurrentGrossSalary(), benefits, contract, married, children);
            if (fromGross.netInHand().subtract(net).abs().compareTo(ctx.grossTolerance()) <= 0) {
                current = fromGross;
                grossSource = "CONFIG";
            }
        }
        if (current == null) current = fromNet(ctx, net, benefits, contract, married, children);

        // Change applied to the chosen base, then the engine in the matching direction (B1)
        PayrollResult projected;
        if (change.base() == SalaryBase.GROSS) {
            BigDecimal newGross = change.apply(current.gross());
            if (newGross.signum() <= 0) throw new NonPositiveSalaryException();
            projected = fromGross(ctx, newGross, benefits, contract, married, children);
        } else {
            BigDecimal newNet = change.apply(net);
            if (newNet.signum() <= 0) throw new NonPositiveSalaryException();
            projected = fromNet(ctx, newNet, benefits, contract, married, children);
        }

        Figures cur = figures(current, benefits);
        Figures proj = figures(projected, benefits);
        BigDecimal deltaCost = proj.loadedCost().subtract(cur.loadedCost());
        return new EmployeeWhatIfRow(
                e.userId(), contract, e.grade(), e.discipline(), cur, proj,
                proj.netInHand().subtract(cur.netInHand()),
                proj.gross().subtract(cur.gross()),
                deltaCost,
                pct(deltaCost, cur.loadedCost()),
                !(current.convergenceOk() && projected.convergenceOk()),
                bc.assumed(), bc.noneSelected(), grossSource, married, children);
    }

    public PayrollResult fromNet(EngineContext ctx, BigDecimal net, List<BenefitCatalogue> benefits,
                                 String contract, Boolean married, Integer children) {
        return simulator.computeFromNet(net, ctx.ps(), ctx.rates(), benefits, ctx.rubriques(), contract,
                ctx.workingDays(), married, children);
    }

    public PayrollResult fromGross(EngineContext ctx, BigDecimal gross, List<BenefitCatalogue> benefits,
                                   String contract, Boolean married, Integer children) {
        return simulator.computeFromGross(gross, ctx.ps(), ctx.rates(), benefits, ctx.rubriques(), contract,
                ctx.workingDays(), married, children);
    }

    /** Same S4 as {@code IndividualSimulationService.persist}: gross + non-taxable benefits. */
    static Figures figures(PayrollResult r, List<BenefitCatalogue> benefits) {
        BigDecimal exempt = benefits.stream()
                .filter(b -> Boolean.FALSE.equals(b.getIsTaxable()))
                .map(b -> b.getMonthlyValue() != null ? b.getMonthlyValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Figures(r.netInHand(), r.netTaxable(), r.gross(),
                r.gross().add(exempt).setScale(4, RoundingMode.HALF_UP),
                r.loadedCost(), r.irppAmount(), r.employeeCharges(), r.employerCharges(), r.taxableBase(),
                r.convergenceOk(), r.iterationsUsed());
    }

    static BigDecimal pct(BigDecimal delta, BigDecimal base) {
        if (base == null || base.signum() == 0) return null;
        return delta.multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
    }

    private List<String> parseCodes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }
}
