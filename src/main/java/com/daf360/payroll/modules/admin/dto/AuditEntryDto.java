package com.daf360.payroll.modules.admin.dto;

import java.time.OffsetDateTime;

/**
 * One line of the payroll change log (« Journal des modifications »), flattened from one of
 * the history tables so the admin screen can list them together, newest first.
 *
 * @param source      {@code EMPLOYEE_CONFIG} (employee_payroll_config_history),
 *                    {@code SALARY_ADVANCE} (salary_advance_history), or
 *                    {@code GENERIC} (audit_trail -- any other in-scope table)
 * @param subjectId   the employee's user id (config) or the advance id (advance); null for GENERIC
 * @param subjectName the employee concerned, from users_ref -- null when unknown;
 *                    for GENERIC, "<table_name> <pk_json>"
 * @param action      config: the change reason; advance: the status reached ({@code to_status});
 *                    GENERIC: INSERT / UPDATE / DELETE
 * @param fromStatus  advance only: the status left, null otherwise (and on creation)
 * @param detail      config: contract type · gross salary; advance: the actor's notes;
 *                    GENERIC: the table name
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
