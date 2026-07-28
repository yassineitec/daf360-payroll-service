package com.daf360.payroll.modules.payroll.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payroll_calculation_sequences")
@Getter @Setter
public class PayrollCalcSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "parameter_set_id")
    private Long parameterSetId;

    @Column(name = "steps", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String steps;

    @Column(name = "active", nullable = false)
    private boolean active = false;
}
