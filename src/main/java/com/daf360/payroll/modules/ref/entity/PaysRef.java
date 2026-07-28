package com.daf360.payroll.modules.ref.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "pays_ref")
@Getter @Setter
public class PaysRef {

    @Id
    private Long id;

    @Column(name = "iso_code", nullable = false)
    private String isoCode;

    @Column(name = "french_label", nullable = false)
    private String frenchLabel;

    @Column(name = "devise", nullable = false)
    private String devise;
}
