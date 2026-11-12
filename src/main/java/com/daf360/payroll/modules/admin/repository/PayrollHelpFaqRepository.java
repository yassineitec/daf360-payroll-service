package com.daf360.payroll.modules.admin.repository;

import com.daf360.payroll.modules.admin.entity.PayrollHelpFaq;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollHelpFaqRepository extends JpaRepository<PayrollHelpFaq, Long> {

    List<PayrollHelpFaq> findAllByOrderByPageKeyAscOrderIndexAscIdAsc();

    List<PayrollHelpFaq> findByIsActiveTrueOrderByPageKeyAscOrderIndexAscIdAsc();
}
