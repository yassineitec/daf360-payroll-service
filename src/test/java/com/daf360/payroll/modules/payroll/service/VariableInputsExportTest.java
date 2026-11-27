package com.daf360.payroll.modules.payroll.service;

import com.daf360.payroll.common.export.ExportLang;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.variable.PayrollVariableInputs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The payslip exports carry the variable elements and the leave balances of the snapshot. */
class VariableInputsExportTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private PayrollVariableInputs sample() {
        return new PayrollVariableInputs(2026, 11, "TND",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                178L, List.of("SATURDAY", "SUNDAY"), 21, LocalDate.of(2024, 1, 1), null, 21, 0,
                new BigDecimal("3000"), "TUN_DEFAULT", new BigDecimal("40"), new BigDecimal("173.33"),
                new BigDecimal("17.3080"), new BigDecimal("142.8571"),
                new PayrollVariableInputs.Overtime(new BigDecimal("75"), new BigDecimal("8"), "COUNTRY",
                        List.of(new PayrollVariableInputs.OvertimeDay(LocalDate.of(2026, 11, 7), "SATURDAY",
                                new BigDecimal("10"), new BigDecimal("8"), true, new BigDecimal("75"), new BigDecimal("242.31"))),
                        new BigDecimal("8"), new BigDecimal("2"), new BigDecimal("242.31")),
                new PayrollVariableInputs.Absences(List.of(new PayrollVariableInputs.AbsenceItem("SANS_SOLDE",
                        "Congé sans solde", "MULTIPLE_DAYS", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11),
                        new BigDecimal("2"), true)), new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("285.71")),
                new PayrollVariableInputs.Autorisations(300, 240, 60, new BigDecimal("1.00"), new BigDecimal("17.31")),
                List.of(new PayrollVariableInputs.Bonus(1L, "Prime", new BigDecimal("200"), "TND", new BigDecimal("200"))),
                new BigDecimal("200.00"),
                List.of(new PayrollVariableInputs.Advance(9L, 1, new BigDecimal("300"), "TND", new BigDecimal("300"), "PLANNED")),
                new BigDecimal("300.00"),
                List.of(new PayrollVariableInputs.Balance("CONGE", new BigDecimal("101"), new BigDecimal("100"), BigDecimal.ONE)),
                true, true, List.of("Avertissement de test"));
    }

    @Test
    void snapshotRoundTripsAndFillsBothSheets() throws Exception {
        PayrollResult r = new PayrollResult();
        r.setVariableInputsJson(mapper.writeValueAsString(sample()));

        PayrollVariableInputs vi = VariableInputsExport.read(mapper, r);
        assertThat(vi).isNotNull();

        var sheet = VariableInputsExport.variableSheet(vi, ExportLang.FR);
        assertThat(sheet.rows()).hasSameSizeAs(sheet.formats());
        assertThat(sheet.rows()).extracting(row -> row.get(0)).contains(
                "Période de paie", "Heures supplémentaires (week-end)", "Absences non payées",
                "Autorisations hors quota", "Primes du mois", "Avance sur salaire", "Avertissements");
        // Advances are shown as a deduction (taken from the net).
        assertThat(sheet.rows()).anySatisfy(row -> {
            assertThat(row.get(0)).isEqualTo("Avance sur salaire");
            assertThat((BigDecimal) row.get(4)).isEqualByComparingTo("-300");
        });

        var balances = VariableInputsExport.balancesSheet(vi, ExportLang.FR);
        assertThat(balances).isNotNull();
        assertThat(balances.rows().get(0)).containsExactly("Congé", new BigDecimal("101"), BigDecimal.ONE, new BigDecimal("100"));

        DecimalFormat n = new DecimalFormat("#,##0.##");
        assertThat(VariableInputsExport.pdfTop(vi, ExportLang.FR, n)).contains("Période de paie", "Congé", "100 j");
        assertThat(VariableInputsExport.pdfVariable(vi, ExportLang.FR, new DecimalFormat("#,##0.00"), n))
                .contains("Éléments variables", "Avertissement de test");
    }

    @Test
    void resultsWithoutSnapshotExportAsBefore() {
        PayrollResult r = new PayrollResult();
        assertThat(VariableInputsExport.read(mapper, r)).isNull();
        assertThat(VariableInputsExport.pdfTop(null, ExportLang.FR, new DecimalFormat())).isEmpty();
        assertThat(VariableInputsExport.pdfVariable(null, ExportLang.FR, new DecimalFormat(), new DecimalFormat())).isEmpty();
    }
}
