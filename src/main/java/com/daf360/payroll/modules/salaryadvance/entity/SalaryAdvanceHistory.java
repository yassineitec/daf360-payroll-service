package com.daf360.payroll.modules.salaryadvance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[salary_advance_history] (V25) — who did what, and when. Append-only; statuses
 * are plain strings so the trail stays readable if a status is ever renamed.
 */
@Entity
@Table(name = "salary_advance_history")
@Getter @Setter
public class SalaryAdvanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "salary_advance_id", nullable = false)
    private Long salaryAdvanceId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
