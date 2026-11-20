package com.stocksafe.alerts.notify;

import com.stocksafe.alerts.detection.DetectionService;
import com.stocksafe.alerts.forecast.ForecastService;
import com.stocksafe.alerts.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** All jobs use ShedLock: with 3 instances running, each job still runs once per tick. */
@Component
@ConditionalOnProperty(name = "stocksafe.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {
    private final JdbcTemplate jdbc;
    private final NotificationDispatcher dispatcher;
    private final DetectionService detection;
    private final ForecastService forecast;
    private final NotificationService notifications;

    public ScheduledJobs(JdbcTemplate jdbc, NotificationDispatcher dispatcher, DetectionService detection,
                         ForecastService forecast, NotificationService notifications) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.detection = detection;
        this.forecast = forecast;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "alerts-notify-dispatch", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
    public void dispatch() {
        dispatcher.dispatchOnce();
    }

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "alerts-expiry-scan", lockAtMostFor = "PT30M")
    public void expiryScan() {
        detection.scanAll(tenants(false));
    }

    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "alerts-forecast", lockAtMostFor = "PT1H")
    public void forecastJob() {
        forecast.computeFor(tenants(true));          // Pro only
    }

    @Scheduled(cron = "0 0 8 * * *")
    @SchedulerLock(name = "alerts-digest", lockAtMostFor = "PT30M")
    public void digest() {
        for (UUID t : tenants(false)) {
            TenantContext.callAs(t, () -> {
                Integer open = jdbc.queryForObject("select count(*) from alerts where status = 'OPEN'", Integer.class);
                if (open != null && open > 0) {
                    notifications.enqueueDigest("You have " + open + " open alert(s). Open StockSafe to review them.");
                }
                return null;
            });
        }
    }

    private List<UUID> tenants(boolean proOnly) {
        return jdbc.queryForList("select tenant_id from tenant_plans" + (proOnly ? " where plan_code = 'pro'" : ""),
                UUID.class);
    }
}
