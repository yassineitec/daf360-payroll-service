package com.daf360.payroll.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create / update body of an admin help question. French is required; English and Arabic
 * are optional — a blank value is stored as null and the screen falls back to French.
 */
public record SaveHelpFaqRequest(
        @NotBlank(message = "La page est obligatoire.")                 @Size(max = 80)   String pageKey,
        @NotBlank(message = "La question en français est obligatoire.") @Size(max = 300)  String questionFr,
        @NotBlank(message = "La réponse en français est obligatoire.")  @Size(max = 2000) String answerFr,
        @Size(max = 300)  String questionEn,
        @Size(max = 2000) String answerEn,
        @Size(max = 300)  String questionAr,
        @Size(max = 2000) String answerAr,
        Integer orderIndex,
        Boolean isActive
) {}
