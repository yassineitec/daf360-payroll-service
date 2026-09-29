package com.daf360.payroll.modules.admin.dto;

import java.time.OffsetDateTime;

/**
 * One line of the payroll change log (« Journal des modifications »), flattened from one of
 * the history tables so the admin screen can list them together, newest first.
 *
 * @param source      {@code EMPLOYEE_CONFIG} (employee_payroll_config_history) or
 *                    {@code SALARY_ADVANCE} (salary_advance_history)
 * @param subjectId   the employee's user id (config) or the advance id (advance)
 * @param subjectName the employee concerned, from users_ref — null when unknown
 * @param action      config: the change reason; advance: the status reached ({@code to_status})
 * @param fromStatus  advance only: the status left, null otherwise (and on creation)
 * @param detail      config: contract type · gross salary; advance: the actor's notes
 */
public record AuditEntryDto(
        String source,
        Long id,
        OffsetDateTime at,
        Long paysId,
        Long actorId,
        String actorName,
        Long subjectId,
        String subjectName,
        String action,
        String fromStatus,
        String detail
) {}
