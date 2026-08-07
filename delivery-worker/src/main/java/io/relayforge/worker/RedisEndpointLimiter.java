package io.relayforge.worker;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class RedisEndpointLimiter {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
            """, Long.class);
    private final StringRedisTemplate redis;

    RedisEndpointLimiter(StringRedisTemplate redis) { this.redis = redis; }

    Permit tryAcquire(UUID endpointId, int concurrency, Duration lease) {
        String owner = UUID.randomUUID().toString();
        for (int slot = 0; slot < concurrency; slot++) {
            String key = "relayforge:endpoint-slot:" + endpointId + ":" + slot;
            Boolean acquired = redis.opsForValue().setIfAbsent(key, owner, lease);
            if (Boolean.TRUE.equals(acquired)) return new Permit(key, owner);
        }
        return null;
    }

    void release(Permit permit) {
        redis.execute(RELEASE, Collections.singletonList(permit.key()), permit.owner());
    }

    record Permit(String key, String owner) {}
}
