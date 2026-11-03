package com.daf360.payroll.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Makes the SessionContextDataSource wrapper the DataSource JPA/JDBC actually use, on top
 * of Spring Boot's autoconfigured Hikari pool (spring.datasource.* in application.yml) --
 * no existing bean definition is replaced, this only adds a @Primary wrapper around it.
 */
@Configuration
public class AuditDataSourceConfig {

    @Bean
    @Primary
    @ConditionalOnMissingBean(SessionContextDataSource.class)
    public DataSource auditedDataSource(DataSource dataSource) {
        return new SessionContextDataSource(dataSource);
    }
}
