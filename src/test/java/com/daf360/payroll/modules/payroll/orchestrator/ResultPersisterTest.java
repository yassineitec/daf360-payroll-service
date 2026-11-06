package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.calculator.ExecutionContext;
import com.daf360.payroll.modules.payroll.dto.RubriqueResultItem;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResultPersisterTest {

    @Mock
    private PayrollResultRepository resultRepo;

    @Test
    void netAndEmployerCostComeFromRubriquesNotTheChargeBridge() {
        ResultPersister persister = new ResultPersister(resultRepo, new ObjectMapper());
        when(resultRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExecutionContext ctx = new ExecutionContext(1L, 7L, 179L, 2026, 1, "CDI",
                BigDecimal.valueOf(22), java.util.Map.of());
        ctx.putVariable("STRATE_1", new BigDecimal("2500.00"));
        ctx.putVariable("STRATE_2", BigDecimal.ZERO);
        ctx.putVariable("STRATE_3", new BigDecimal("619.25")); // mixed EE+ER, unused by the fix
        ctx.putVariable("STRATE_4", new BigDecimal("2659.00"));
        ctx.putVariable("STRATE_5", new BigDecimal("100.00")); // IRPP + CSS
        // Simulates the old-model charge bridge still running (per spec: untouched, just unread).
        ctx.putVariable("CHARGES_ER", new BigDecimal("9999.99"));

        List<RubriqueResultItem> items = List.of(
                item("CNSS_SALARIE", "COTISATION", "EMPLOYEE", 3, "241.90"),
                item("CNSS_PATRONAL", "COTISATION", "EMPLOYER", 3, "414.25"),
                item("TFP", "COTISATION", "EMPLOYER", 3, "50.00"),
                item("FOPROLOS", "COTISATION", "EMPLOYER", 3, "25.00"),
                item("AT_PATRONAL", "COTISATION", "EMPLOYER", 3, "25.00"),
                item("ASSIETTE_IRPP", "TAXE", null, 4, "2659.00"),
                item("IRPP", "TAXE", null, 5, "80.00"),
                item("CSS", "TAXE", null, 5, "20.00")
        );

        PayrollResult result = persister.persist(ctx, items, 7L, "test", true, null);

        // gross (2500) - employee charges (241.90) - taxes (STRATE_5 = 100.00)
        assertThat(result.getAggregateNet()).isEqualByComparingTo("2158.10");
        // employer charges = CNSS_PATRONAL + TFP + FOPROLOS + AT_PATRONAL, NOT CHARGES_ER
        assertThat(result.getAggregateEmployerCharges()).isEqualByComparingTo("514.25");
        assertThat(result.getLoadedCost()).isEqualByComparingTo("3014.25"); // 2500 + 514.25
    }

    private static RubriqueResultItem item(String code, String nature, String payer, int strate, String amount) {
        return RubriqueResultItem.builder()
                .rubriqueCode(code)
                .nature(nature)
                .payer(payer)
                .strate(strate)
                .amount(new BigDecimal(amount))
                .build();
    }
}
