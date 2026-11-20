package com.stocksafe.core.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** On every start, make sure stock_movements has partitions for this month and the next 3. */
@Component
public class PartitionInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public PartitionInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("select ensure_movement_partitions(3)");
    }
}
