package com.daf360.payroll.modules.calibration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_calibration_imports")
@Getter @Setter
public class CalibrationImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "period", nullable = false, length = 7)
    private String period;

    @Column(name = "import_status", nullable = false, length = 20)
    private String importStatus = "PENDING";

    @Column(name = "imported_at")
    private OffsetDateTime importedAt;

    @Column(name = "imported_by", length = 100)
    private String importedBy;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "global_precision_pct", precision = 10, scale = 4)
    private BigDecimal globalPrecisionPct;

    @Column(name = "parameter_set_id")
    private Long parameterSetId;

    @Column(name = "triggered_at", nullable = false)
    private OffsetDateTime triggeredAt = OffsetDateTime.now();

    @Column(name = "deadline_j5")
    private OffsetDateTime deadlineJ5;
}
