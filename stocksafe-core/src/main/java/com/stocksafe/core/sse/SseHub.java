package com.stocksafe.core.sse;

import com.stocksafe.core.common.Json;
import com.stocksafe.core.tenant.TenantContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live updates per shop. Every event is saved with an increasing id, then pushed.
 * A browser that reconnects sends Last-Event-ID and receives what it missed.
 * Note: pushes reach browsers connected to THIS instance. With more instances, each browser
 * still catches up from the saved log on its next reconnect (see README).
 */
@Component
public class SseHub {
    private final JdbcTemplate jdbc;
    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseHub(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SseEmitter subscribe(UUID shopId, Long lastEventId) {
        SseEmitter em = new SseEmitter(30 * 60 * 1000L);
        Set<SseEmitter> set = emitters.computeIfAbsent(shopId, k -> new CopyOnWriteArraySet<>());
        set.add(em);
        Runnable remove = () -> set.remove(em);
        em.onCompletion(remove);
        em.onTimeout(remove);
        em.onError(e -> remove.run());
        try {
            em.send(SseEmitter.event().name("ready").data("{}"));
            if (lastEventId != null) {          // catch up
                List<Map<String, Object>> missed = jdbc.queryForList(
                        "select id, type, data::text as data from sse_events where shop_id = ? and id > ? "
                                + "order by id limit 200", shopId, lastEventId);
                for (Map<String, Object> m : missed) {
                    em.send(SseEmitter.event().id(String.valueOf(m.get("id"))).name((String) m.get("type"))
                            .data(m.get("data")));
                }
            }
        } catch (IOException e) {
            remove.run();
        }
        return em;
    }

    /** Saves the event, and pushes it after the surrounding transaction commits (never for a rolled-back change). */
    public void publish(UUID shopId, String type, Map<String, Object> data) {
        String json = Json.write(data);
        Long id = jdbc.queryForObject("insert into sse_events(tenant_id, shop_id, type, data) "
                + "values (?,?,?,?::jsonb) returning id", Long.class, TenantContext.require(), shopId, type, json);
        Runnable push = () -> push(shopId, id, type, json);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { push.run(); }
            });
        } else {
            push.run();
        }
    }

    private void push(UUID shopId, Long id, String type, String json) {
        for (SseEmitter em : emitters.getOrDefault(shopId, Set.of())) {
            try {
                em.send(SseEmitter.event().id(String.valueOf(id)).name(type).data(json));
            } catch (IOException | IllegalStateException e) {
                emitters.get(shopId).remove(em);
            }
        }
    }

    /** Keeps proxies from closing idle streams. */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        emitters.values().forEach(set -> set.forEach(em -> {
            try {
                em.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                set.remove(em);
            }
        }));
    }

    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "core-sse-cleanup", lockAtMostFor = "PT5M")
    public void cleanup() {
        // runs without a tenant: use the SECURITY DEFINER helper
        jdbc.execute("select cleanup_sse_events()");
    }
}
