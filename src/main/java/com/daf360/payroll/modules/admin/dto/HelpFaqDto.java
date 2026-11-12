package com.daf360.payroll.modules.admin.dto;

import java.time.LocalDateTime;

/** One admin-typed help question, as read by the admin section and the help assistant. */
public record HelpFaqDto(
        Long id,
        String pageKey,
        String questionFr,
        String answerFr,
        String questionEn,
        String answerEn,
        String questionAr,
        String answerAr,
        Integer orderIndex,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
