package com.daf360.payroll.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps whichever DataSource bean Spring Boot autoconfigures (spring.datasource.* in
 * application.yml) with SessionContextDataSource, in place -- no new competing bean is
 * introduced, so there is nothing for @Primary/@Qualifier to disambiguate.
 *
 * A @Bean factory method taking/returning DataSource (even @Primary + @Qualifier-pinned
 * to the autoconfigured bean's name) causes "Requested bean is currently in creation" or
 * "No qualifying bean" depending on the exact name Spring Boot 4.x's reorganized
 * autoconfiguration happens to use here -- confirmed by actually starting the service,
 * not just by reading the code. A BeanPostProcessor sidesteps the problem entirely: it
 * decorates whatever DataSource bean already exists, by whatever name, after Spring has
 * already finished resolving it as a dependency everywhere else.
 */
@Configuration
public class AuditDataSourceConfig {

    @Bean
    public static BeanPostProcessor sessionContextDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof SessionContextDataSource)) {
                    return new SessionContextDataSource(ds);
                }
                return bean;
            }
        };
    }
}
