package com.daf360.payroll.modules.payroll.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payroll_rubriques")
@Getter @Setter
public class PayrollRubriqueDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 200)
    private String labelFr;

    @Column(name = "label_en", length = 200)
    private String labelEn;

    @Column(name = "strate", nullable = false)
    private int strate;

    @Column(name = "nature", nullable = false, length = 20)
    private String nature;

    @Column(name = "mode_calcul", nullable = false, length = 30)
    private String modeCalcul;

    @Column(name = "assiette_code", length = 20)
    private String assietteCode;

    @Column(name = "param_key_taux", length = 50)
    private String paramKeyTaux;

    @Column(name = "param_key_plafond", length = 50)
    private String paramKeyPlafond;

    @Column(name = "param_key_bareme", length = 50)
    private String paramKeyBareme;

    @Column(name = "formula_expression", length = 1000)
    private String formulaExpression;

    @Column(name = "contract_type_filter", length = 200)
    private String contractTypeFilter;

    @Column(name = "periodicite", nullable = false, length = 20)
    private String periodicite = "MENSUEL";

    @Column(name = "prorata_applicable", nullable = false)
    private boolean prorataApplicable = false;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
