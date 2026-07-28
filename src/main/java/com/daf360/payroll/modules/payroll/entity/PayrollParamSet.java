package com.daf360.payroll.modules.payroll.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_parameter_sets")
@Getter @Setter
public class PayrollParamSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "parameters", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String parameters;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "approved_by_hr", length = 100)
    private String approvedByHr;

    @Column(name = "approved_at_hr")
    private OffsetDateTime approvedAtHr;

    @Column(name = "approved_by_finance", length = 100)
    private String approvedByFinance;

    @Column(name = "approved_at_finance")
    private OffsetDateTime approvedAtFinance;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
