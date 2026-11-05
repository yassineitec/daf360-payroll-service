package com.daf360.payroll.modules.parameterset.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "social_charge_rates")
@Getter @Setter
public class SocialChargeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "contract_type", nullable = false)
    private String contractType;  // CDI|CDD|STAGE|CIVP

    @Column(name = "charge_code", nullable = false)
    private String chargeCode;

    @Column(name = "charge_label", nullable = false)
    private String chargeLabel;

    @Column(name = "employee_rate", nullable = false)
    private BigDecimal employeeRate = BigDecimal.ZERO;

    @Column(name = "employer_rate", nullable = false)
    private BigDecimal employerRate = BigDecimal.ZERO;

    /**
     * GROSS|CAPPED_GROSS|FIXED|TAXABLE_BASE2. {@code TAXABLE_BASE2} means this charge's base
     * is the post-abattement/family-deduction monthly taxable base (the same figure IRPP is
     * computed on, e.g. Tunisia's CSS) rather than gross: it is computed after IRPP in
     * {@code PayrollSimulatorService}, excluded from the employee-charges total that reduces
     * the IRPP base, but still deducted from net in hand and (employer side) added to the
     * loaded cost.
     */
    @Column(name = "base_calculation", nullable = false)
    private String baseCalculation = "GROSS";

    @Column(name = "cap_amount")
    private BigDecimal capAmount;

    // ── Formula support (V19) ──────────────────────────────────────────────────

    /**
     * Arithmetic expression for the employee-side charge amount (optional).
     * When set, overrides the standard {@code employee_rate × base} computation.
     * Available variables: {@code BRUT}, results of prior charges ({@code {CODE}_EE}, {@code {CODE}_ER}).
     * Example: {@code "BRUT * 0.0918"} or {@code "(BRUT - PRIME) * 0.05"}.
     */
    @Column(name = "formula_ee", length = 1000)
    private String formulaEe;

    /**
     * Arithmetic expression for the employer-side charge amount (optional).
     * Same variable context as {@link #formulaEe}.
     */
    @Column(name = "formula_er", length = 1000)
    private String formulaEr;

    /**
     * Evaluation order within a parameter set; lower = evaluated first.
     * A formula charge with {@code eval_order=20} can reference results of charges
     * with {@code eval_order=10} via their {@code {CODE}_EE} / {@code {CODE}_ER} variables.
     */
    @Column(name = "eval_order", nullable = false)
    private Integer evalOrder = 0;
}
