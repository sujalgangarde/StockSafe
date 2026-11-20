package com.stocksafe.alerts.tenant;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TenantDataSourceConfig {

    /** Wrap the auto-configured pool. JPA and JdbcTemplate then both get tenant-aware connections. */
    @Bean
    static BeanPostProcessor tenantDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantDataSource)) {
                    return new TenantDataSource(ds);
                }
                return bean;
            }
        };
    }
}
