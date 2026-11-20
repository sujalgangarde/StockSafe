package com.stocksafe.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// We use JWT, so drop the default in-memory user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StockSafeCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockSafeCoreApplication.class, args);
    }
}
