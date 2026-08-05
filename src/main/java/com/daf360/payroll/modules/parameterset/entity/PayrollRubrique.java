package com.daf360.payroll.modules.parameterset.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_rubriques_legacy")
@Getter @Setter
public class PayrollRubrique {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parameter_set_id", nullable = false)
    private Long parameterSetId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 200)
    private String labelFr;

    @Column(name = "label_en", length = 200)
    private String labelEn;

    @Column(name = "nature", nullable = false, length = 20)
    private String nature;  // AVANTAGE|INDEMNITE|PRIME|RETENUE

    @Column(name = "calc_mode", nullable = false, length = 30)
    private String calcMode;  // FIXE_MENSUEL|FIXE_JOURNALIER|POURCENTAGE_BRUT|POURCENTAGE_CHARGES|POURCENTAGE_PLAFONNE

    @Column(name = "amount", precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "rate", precision = 10, scale = 6)
    private BigDecimal rate;

    @Column(name = "cap_amount", precision = 18, scale = 4)
    private BigDecimal capAmount;

    @Column(name = "employer_share_pct", nullable = false, precision = 10, scale = 6)
    private BigDecimal employerSharePct = BigDecimal.ZERO;

    @Column(name = "employee_share_pct", nullable = false, precision = 10, scale = 6)
    private BigDecimal employeeSharePct = BigDecimal.ZERO;

    @Column(name = "is_subject_to_social_charges", nullable = false)
    private Boolean isSubjectToSocialCharges = false;

    @Column(name = "is_subject_to_irpp", nullable = false)
    private Boolean isSubjectToIrpp = true;

    @Column(name = "direction", nullable = false, length = 10)
    private String direction = "CREDIT";  // CREDIT|DEBIT

    @Column(name = "contract_types", length = 100)
    private String contractTypes;  // null = all; "CDI,CDD" = specific

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
