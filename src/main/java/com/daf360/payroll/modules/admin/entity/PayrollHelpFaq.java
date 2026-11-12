package com.daf360.payroll.modules.admin.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A question / answer typed by a payroll super-administrator (Administration › Assistant &
 * FAQ), shown by the help assistant next to the FAQ shipped with the app — the payroll
 * counterpart of `help_faq` (daf360-rh-service) and `fact_help_faq` (daf360-facturation-service).
 * Company-wide, not per country. French is required; English and Arabic fall back to it.
 * Table created by V27__payroll_help_faq.sql.
 */
@Entity
@Table(name = "payroll_help_faq")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollHelpFaq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Guide key of the page the question is about (front `GUIDE_PAGES.guideKey`, e.g. "simulator"). */
    @Column(name = "page_key", nullable = false, length = 80, columnDefinition = "nvarchar(80)")
    private String pageKey;

    @Column(name = "question_fr", nullable = false, length = 300, columnDefinition = "nvarchar(300)")
    private String questionFr;

    @Column(name = "answer_fr", nullable = false, length = 2000, columnDefinition = "nvarchar(2000)")
    private String answerFr;

    @Column(name = "question_en", length = 300, columnDefinition = "nvarchar(300)")
    private String questionEn;

    @Column(name = "answer_en", length = 2000, columnDefinition = "nvarchar(2000)")
    private String answerEn;

    @Column(name = "question_ar", length = 300, columnDefinition = "nvarchar(300)")
    private String questionAr;

    @Column(name = "answer_ar", length = 2000, columnDefinition = "nvarchar(2000)")
    private String answerAr;

    @Builder.Default
    @Column(name = "order_index", nullable = false)
    private Integer orderIndex = 0;

    /** Published: shown by the assistant. Inactive = kept but hidden. */
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
