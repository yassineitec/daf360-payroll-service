package com.daf360.payroll.modules.payroll.leavesettings;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[payroll_period_settings] (V42) — the pay period of one country (HR pays id).
 * Start 1 = calendar month. Otherwise month M runs from {@code periodStartDay} of M−1 to
 * {@code periodEndDay} of M, with end = start − 1 so consecutive months neither overlap nor
 * leave a gap (also a CHECK constraint). No row = calendar month.
 */
@Entity
@Table(name = "payroll_period_settings")
@Getter @Setter
public class PayrollPeriodSetting {

    @Id
    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "period_start_day", nullable = false)
    private Integer periodStartDay = 1;

    @Column(name = "period_end_day")
    private Integer periodEndDay;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
