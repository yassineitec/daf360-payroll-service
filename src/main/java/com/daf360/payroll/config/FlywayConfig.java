package com.daf360.payroll.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Explicit Flyway bean — required because Spring Boot 4.x removed FlywayAutoConfiguration.
 */
@Configuration
public class FlywayConfig {

    @Value("${spring.flyway.locations:classpath:db/migration}")
    private String locations;

    @Value("${spring.flyway.baseline-on-migrate:true}")
    private boolean baselineOnMigrate;

    @Value("${spring.flyway.baseline-version:1}")
    private String baselineVersion;

    @Value("${spring.flyway.validate-on-migrate:false}")
    private boolean validateOnMigrate;

    @Value("${spring.flyway.enabled:true}")
    private boolean enabled;

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        if (!enabled) {
            return Flyway.configure().dataSource(dataSource).load();
        }
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.split(","))
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion)
                .validateOnMigrate(validateOnMigrate)
                .ignoreMigrationPatterns("*:missing")
                .load();
    }
}
