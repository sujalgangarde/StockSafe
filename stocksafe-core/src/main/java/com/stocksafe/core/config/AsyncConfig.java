package com.stocksafe.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /** Runs CSV imports. On shutdown it waits for running work (graceful). */
    @Bean(name = "importExecutor")
    ThreadPoolTaskExecutor importExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(2);
        e.setQueueCapacity(50);
        e.setThreadNamePrefix("import-");
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(20);
        return e;
    }
}
