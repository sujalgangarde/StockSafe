package com.stocksafe.core.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** ShedLock: a scheduled job runs on ONE instance at a time. The lock lives in the shedlock table. */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class SchedulingConfig {
    @Bean
    LockProvider lockProvider(DataSource ds) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(ds)).usingDbTime().build());
    }
}
