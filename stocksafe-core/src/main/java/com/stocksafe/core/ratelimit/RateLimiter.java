package com.stocksafe.core.ratelimit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Fixed-window counter. Redis is shared by all instances. If Redis is down we fail open (do not block users). */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final boolean useRedis;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, long[]> memory = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${stocksafe.ratelimit.store}") String store, ObjectProvider<StringRedisTemplate> redis) {
        this.useRedis = store.equals("redis");
        this.redis = redis.getIfAvailable();
    }

    public boolean allow(String key, int limit, int windowSeconds) {
        long window = System.currentTimeMillis() / 1000 / windowSeconds;
        if (useRedis && redis != null) {
            try {
                String k = "rl:" + key + ":" + window;
                Long n = redis.opsForValue().increment(k);
                if (n != null && n == 1) redis.expire(k, Duration.ofSeconds(windowSeconds + 1L));
                return n == null || n <= limit;
            } catch (RuntimeException e) {
                log.warn("Rate limit store unavailable, allowing request: {}", e.toString());
                return true;
            }
        }
        long[] slot = memory.compute(key, (k, v) -> (v == null || v[0] != window) ? new long[]{window, 1} : new long[]{window, v[1] + 1});
        if (memory.size() > 50_000) memory.clear();            // crude cleanup
        return slot[1] <= limit;
    }
}
