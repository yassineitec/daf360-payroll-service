package com.daf360.payroll.modules.ref.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users_ref")
@Getter @Setter
public class UsersRef {

    @Id
    private Long id;

    @Column(name = "azure_oid")
    private String azureOid;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email")
    private String email;

    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "role_name")
    private String roleName;
}
