package com.daf360.payroll.modules.calibration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** F.04 — one of the two cost lines auto-created when a ParameterSet is activated. */
@Getter
@Setter
@Entity
@Table(name = "payroll_budget_lines")
public class PayrollBudgetLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    /** YYYY-MM period inherited from the triggering calibration cycle. */
    @Column(name = "period", nullable = false, length = 7)
    private String period;

    /** EMPLOYEE_NET — total monthly net salaries; EMPLOYER_LOADED — total loaded cost. */
    @Column(name = "line_type", nullable = false, length = 30)
    private String lineType;

    @Column(name = "monthly_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal monthlyAmount;

    @Column(name = "monthly_eur", precision = 18, scale = 4)
    private BigDecimal monthlyEur;

    @Column(name = "monthly_chf", precision = 18, scale = 4)
    private BigDecimal monthlyChf;

    @Column(name = "headcount")
    private Integer headcount;

    @Column(name = "local_currency", length = 10)
    private String localCurrency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
