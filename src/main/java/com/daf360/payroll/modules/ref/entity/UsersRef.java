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

    /**
     * False for a test, duplicate or machine account. The row is still synced — other tables
     * reference users_ref — so only the PICKERS filter on this.
     */
    @Column(name = "is_employee", nullable = false)
    private Boolean isEmployee = true;
}
