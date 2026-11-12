package com.daf360.payroll.modules.admin.service;

import com.daf360.payroll.modules.admin.dto.HelpFaqDto;
import com.daf360.payroll.modules.admin.dto.SaveHelpFaqRequest;
import com.daf360.payroll.modules.admin.entity.PayrollHelpFaq;
import com.daf360.payroll.modules.admin.repository.PayrollHelpFaqRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Administration › Assistant & FAQ — same service as `HelpFaqService` (daf360-rh-service) and
 * `FactHelpFaqService` (daf360-facturation-service). Permissions are checked on the controller
 * (writes and the full list: PAYROLL_SUPER_ADMIN; published questions: every signed-in user).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PayrollHelpFaqService {

    private final PayrollHelpFaqRepository repo;

    /** Every question, published or not — the admin section. */
    @Transactional(readOnly = true)
    public List<HelpFaqDto> listAll() {
        return repo.findAllByOrderByPageKeyAscOrderIndexAscIdAsc().stream().map(this::toDto).toList();
    }

    /** Published questions only — the help assistant. */
    @Transactional(readOnly = true)
    public List<HelpFaqDto> listActive() {
        return repo.findByIsActiveTrueOrderByPageKeyAscOrderIndexAscIdAsc().stream().map(this::toDto).toList();
    }

    public HelpFaqDto create(SaveHelpFaqRequest req, Long actorId) {
        PayrollHelpFaq faq = PayrollHelpFaq.builder()
                .createdAt(LocalDateTime.now())
                .createdBy(actorId)
                .build();
        apply(faq, req);
        return toDto(repo.save(faq));
    }

    public HelpFaqDto update(Long id, SaveHelpFaqRequest req, Long actorId) {
        PayrollHelpFaq faq = find(id);
        apply(faq, req);
        faq.setUpdatedAt(LocalDateTime.now());
        faq.setUpdatedBy(actorId);
        return toDto(repo.save(faq));
    }

    public HelpFaqDto setActive(Long id, boolean active, Long actorId) {
        PayrollHelpFaq faq = find(id);
        faq.setIsActive(active);
        faq.setUpdatedAt(LocalDateTime.now());
        faq.setUpdatedBy(actorId);
        return toDto(repo.save(faq));
    }

    public void delete(Long id) {
        repo.delete(find(id));
    }

    private PayrollHelpFaq find(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Question d'aide introuvable (id " + id + ")."));
    }

    private void apply(PayrollHelpFaq faq, SaveHelpFaqRequest req) {
        faq.setPageKey(req.pageKey().trim());
        faq.setQuestionFr(req.questionFr().trim());
        faq.setAnswerFr(req.answerFr().trim());
        faq.setQuestionEn(blankToNull(req.questionEn()));
        faq.setAnswerEn(blankToNull(req.answerEn()));
        faq.setQuestionAr(blankToNull(req.questionAr()));
        faq.setAnswerAr(blankToNull(req.answerAr()));
        if (req.orderIndex() != null) faq.setOrderIndex(req.orderIndex());
        if (req.isActive() != null) faq.setIsActive(req.isActive());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private HelpFaqDto toDto(PayrollHelpFaq f) {
        return new HelpFaqDto(f.getId(), f.getPageKey(),
                f.getQuestionFr(), f.getAnswerFr(),
                f.getQuestionEn(), f.getAnswerEn(),
                f.getQuestionAr(), f.getAnswerAr(),
                f.getOrderIndex(), f.getIsActive(),
                f.getCreatedAt(), f.getUpdatedAt());
    }
}
