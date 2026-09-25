package com.daf360.payroll.modules.salaryadvance.service;

import com.daf360.payroll.modules.ref.repository.UsersRefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient;
import com.daf360.payroll.modules.salaryadvance.client.HrAdvanceClient.EmployeeFacts;
import com.daf360.payroll.modules.salaryadvance.dto.SalaryAdvanceDtos.*;
import com.daf360.payroll.modules.salaryadvance.entity.*;
import com.daf360.payroll.modules.salaryadvance.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 *  1. Schedule — equal lines, remainder on the last, sums to the amount
 *  2. Schedule — consecutive months across a year boundary
 *  3. Eligibility — no RH profile → NO_PROFILE
 *  4. Eligibility — all blockers listed at once (seniority, open advance, departure)
 *  5. Submit — goes to PENDING_FINANCE; no ceiling on the amount
 *  6. Submit — period above the rule's maximum is refused
 *  7. Finance approve — nobody approves their own advance
 *  8. Withdraw — refused once finance has decided
 *  9. Deducting the last line closes the advance (REPAID, nothing outstanding)
 */
@ExtendWith(MockitoExtension.class)
class SalaryAdvanceServiceTest {

    @Mock SalaryAdvanceRepository            advanceRepo;
    @Mock SalaryAdvanceInstallmentRepository installmentRepo;
    @Mock SalaryAdvancePolicyRepository      policyRepo;
    @Mock SalaryAdvanceHistoryRepository     historyRepo;
    @Mock UsersRefRepository                 usersRefRepo;
    @Mock UserContextService                 userContext;
    @Mock HrAdvanceClient                    hr;

    @InjectMocks SalaryAdvanceService service;

    private static final Long EMPLOYEE = 100L;
    private static final Long PAYROLL_USER = 200L;
    private static final Long PAYS = 179L;
    private static final String NOW = YearMonth.now().toString();

    private EmployeeFacts facts(boolean active, LocalDate hired, boolean leaving) {
        return new EmployeeFacts(EMPLOYEE, 1L, "Ali Ben Salah", PAYS, hired,
                active ? "ACTIVE" : "TERMINATED", active, leaving, "M-042", NOW);
    }

    private SalaryAdvancePolicy policy() {
        SalaryAdvancePolicy p = new SalaryAdvancePolicy();
        p.setPaysId(PAYS);
        p.setCurrency("TND");
        p.setMaxInstallments(3);
        p.setMinSeniorityMonths(3);
        p.setIsActive(true);
        return p;
    }

    private SalaryAdvance advance(SalaryAdvanceStatus status) {
        SalaryAdvance a = new SalaryAdvance();
        a.setId(7L);
        a.setProfileUserId(EMPLOYEE);
        a.setPaysId(PAYS);
        a.setCurrency("TND");
        a.setAmount(new BigDecimal("900.000"));
        a.setInstallments(3);
        a.setFirstDeductionMonth(YearMonth.now().atDay(1));
        a.setStatus(status);
        return a;
    }

    private void eligibleEmployee() {
        when(hr.employee(EMPLOYEE)).thenReturn(Optional.of(facts(true, LocalDate.now().minusYears(2), false)));
        when(policyRepo.findByPaysId(PAYS)).thenReturn(Optional.of(policy()));
    }

    // ── 1–2. Schedule ────────────────────────────────────────────────────────

    @Test
    void schedule_splitsEqually_lastLineTakesRemainder() {
        List<SalaryAdvanceInstallment> lines =
                SalaryAdvanceService.buildSchedule(7L, new BigDecimal("1000.000"), 3, YearMonth.of(2026, 10));

        assertThat(lines).extracting(SalaryAdvanceInstallment::getAmount)
                .containsExactly(new BigDecimal("333.333"), new BigDecimal("333.333"), new BigDecimal("333.334"));
        assertThat(lines).allMatch(l -> l.getStatus() == InstallmentStatus.PLANNED);
    }

    @Test
    void schedule_monthsAreConsecutive_acrossYearEnd() {
        List<SalaryAdvanceInstallment> lines =
                SalaryAdvanceService.buildSchedule(7L, new BigDecimal("300"), 3, YearMonth.of(2026, 11));

        assertThat(lines).extracting(SalaryAdvanceInstallment::period)
                .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
    }

    // ── 3–4. Eligibility ─────────────────────────────────────────────────────

    @Test
    void eligibility_withoutRhProfile_isNoProfile() {
        when(hr.employee(EMPLOYEE)).thenReturn(Optional.empty());

        Eligibility e = service.eligibility(EMPLOYEE);

        assertThat(e.eligible()).isFalse();
        assertThat(e.blockers()).containsExactly("NO_PROFILE");
    }

    @Test
    void eligibility_listsEveryBlockerAtOnce() {
        when(hr.employee(EMPLOYEE)).thenReturn(Optional.of(facts(true, LocalDate.now().minusMonths(1), true)));
        when(policyRepo.findByPaysId(PAYS)).thenReturn(Optional.of(policy()));
        when(advanceRepo.existsByProfileUserIdAndStatusIn(EMPLOYEE, SalaryAdvanceStatus.OPEN)).thenReturn(true);

        Eligibility e = service.eligibility(EMPLOYEE);

        assertThat(e.eligible()).isFalse();
        assertThat(e.blockers()).containsExactlyInAnyOrder("SENIORITY", "OPEN_ADVANCE", "OFFBOARDING");
    }

    // ── 5–6. Submit ──────────────────────────────────────────────────────────

    @Test
    void submit_largeAmount_goesToFinance() {
        eligibleEmployee();
        when(advanceRepo.save(any(SalaryAdvance.class))).thenAnswer(inv -> {
            SalaryAdvance a = inv.getArgument(0);
            if (a.getId() == null) a.setId(7L);
            return a;
        });

        AdvanceDto dto = service.submit(new SubmitRequest(new BigDecimal("250000"), 3, NOW, null), EMPLOYEE);

        assertThat(dto.status()).isEqualTo(SalaryAdvanceStatus.PENDING_FINANCE);
        assertThat(dto.amount()).isEqualByComparingTo("250000");
        assertThat(dto.currency()).isEqualTo("TND");
    }

    @Test
    void submit_periodAboveMaximum_isRefused() {
        eligibleEmployee();

        assertThatThrownBy(() -> service.submit(new SubmitRequest(new BigDecimal("500"), 4, NOW, null), EMPLOYEE))
                .isInstanceOf(IllegalArgumentException.class);
        verify(advanceRepo, never()).save(any());
    }

    // ── 7–8. Decisions ───────────────────────────────────────────────────────

    @Test
    void approve_ownAdvance_isForbidden() {
        when(advanceRepo.findById(7L)).thenReturn(Optional.of(advance(SalaryAdvanceStatus.PENDING_FINANCE)));

        assertThatThrownBy(() -> service.approve(7L, null, EMPLOYEE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cancelMine_afterDecision_isRefused() {
        when(advanceRepo.findById(7L)).thenReturn(Optional.of(advance(SalaryAdvanceStatus.APPROVED)));

        assertThatThrownBy(() -> service.cancelMine(7L, null, EMPLOYEE))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 9. Repayment ─────────────────────────────────────────────────────────

    @Test
    void markDeducted_lastLine_closesTheAdvance() {
        SalaryAdvance repaying = advance(SalaryAdvanceStatus.REPAYING);
        repaying.setOutstandingAmount(new BigDecimal("300.000"));
        SalaryAdvanceInstallment last = new SalaryAdvanceInstallment();
        last.setId(33L);
        last.setSalaryAdvanceId(7L);
        last.setSeq(3);
        last.setPeriod(YearMonth.now());
        last.setAmount(new BigDecimal("300.000"));
        last.setStatus(InstallmentStatus.PLANNED);

        when(installmentRepo.findAllById(any())).thenReturn(List.of(last));
        when(advanceRepo.findAllById(any())).thenReturn(List.of(repaying));
        when(installmentRepo.existsBySalaryAdvanceIdAndStatus(7L, InstallmentStatus.PLANNED)).thenReturn(false);
        when(advanceRepo.save(any(SalaryAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        int updated = service.markDeducted(new InstallmentActionRequest(List.of(33L), null), PAYROLL_USER);

        assertThat(updated).isEqualTo(1);
        assertThat(last.getStatus()).isEqualTo(InstallmentStatus.DEDUCTED);
        assertThat(repaying.getStatus()).isEqualTo(SalaryAdvanceStatus.REPAID);
        assertThat(repaying.getOutstandingAmount()).isEqualByComparingTo("0");
    }
}
