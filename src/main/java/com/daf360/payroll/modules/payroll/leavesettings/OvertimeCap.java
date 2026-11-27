package com.daf360.payroll.modules.payroll.leavesettings;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[payroll_overtime_caps] (V43) — most weekend-overtime hours paid per day:
 * {@code EMPLOYEE} (one employee, any country) or {@code ROLE} (one RH role in one country).
 * Resolution: employee → role → engine parameter PLAFOND_HS_JOUR → 8 h.
 */
@Entity
@Table(name = "payroll_overtime_caps")
@Getter @Setter
public class OvertimeCap {

    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String ROLE = "ROLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope", nullable = false, length = 10)
    private String scope;

    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "profile_user_id")
    private Long profileUserId;

    @Column(name = "cap_hours", nullable = false)
    private BigDecimal capHours;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
