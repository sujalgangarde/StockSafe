package com.stocksafe.alerts.notify;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Talks to the "providers" (mock). Each call has a 3 second timeout, and each channel has a circuit breaker:
 * after repeated failures the breaker opens and calls fail fast for 30 seconds instead of piling up.
 */
@Component
public class NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).build());
    private final TimeLimiter timeLimiter = TimeLimiter.of(Duration.ofSeconds(3));
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile double failRate;

    public NotificationSender(@Value("${stocksafe.notify.fail-rate}") double failRate) {
        this.failRate = failRate;
    }

    /** Test and demo hook. */
    public void setFailRate(double failRate) { this.failRate = failRate; }

    public void send(String channel, String recipient, String subject, String body) throws Exception {
        CircuitBreaker cb = breakers.circuitBreaker(channel);
        Callable<Object> timed = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> pool.submit((Callable<Object>) () -> {
                    deliver(channel, recipient, subject);
                    return null;
                }));
        CircuitBreaker.decorateCallable(cb, timed).call();
    }

    // A real build would call an email API or a WhatsApp provider here.
    private void deliver(String channel, String recipient, String subject) {
        if (failRate > 0 && ThreadLocalRandom.current().nextDouble() < failRate) {
            throw new IllegalStateException("simulated provider failure");
        }
        log.info("MOCK {} to {}: {}", channel, recipient, subject);
    }
}
