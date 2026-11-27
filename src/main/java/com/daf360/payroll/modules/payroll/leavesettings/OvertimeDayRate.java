package com.daf360.payroll.modules.payroll.leavesettings;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[payroll_overtime_day_rates] (V43) — the weekend-overtime majoration (%) of one
 * weekend day in one country (HR pays id). A day without a row uses the engine parameter
 * TAUX_MAJORATION_HS_WEEKEND.
 */
@Entity
@Table(name = "payroll_overtime_day_rates")
@Getter @Setter
public class OvertimeDayRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    /** DayOfWeek name: SATURDAY, SUNDAY, FRIDAY… */
    @Column(name = "day_of_week", nullable = false, length = 10)
    private String dayOfWeek;

    @Column(name = "majoration_pct", nullable = false)
    private BigDecimal majorationPct;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
