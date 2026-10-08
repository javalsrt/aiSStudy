package com.znxsgl.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式幂等/锁服务。
 *
 * 业务语义：
 * - tryAcquire(key, ttl)：抢占一个标记，成功返回 true；失败说明其他实例正在处理或已处理；
 * - release(key)：业务失败时释放标记，允许用户重试；
 * - isProcessed(key)：只读判断，不写入。
 *
 * Redis 不可用时自动回退到 JVM 本地 ConcurrentHashMap，保证单机仍然可用；
 * 多实例部署时必须保证 Redis 可用，否则退化为单机语义。
 */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisIdempotencyService {

    private static final String PREFIX = "znxsgl:idem:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Map<String, Long> localCache = new ConcurrentHashMap<>();

    public RedisIdempotencyService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    public boolean tryAcquire(String key, Duration ttl) {
        String redisKey = PREFIX + key;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                Boolean ok = redis.opsForValue().setIfAbsent(redisKey, "1", ttl);
                return Boolean.TRUE.equals(ok);
            }
        } catch (Exception ignored) {
            // Redis 故障时回退本地，避免业务不可用
        }

        long now = System.currentTimeMillis();
        localCache.entrySet().removeIf(e -> now - e.getValue() > ttl.toMillis());
        Long previous = localCache.putIfAbsent(redisKey, now);
        return previous == null;
    }

    public void release(String key) {
        String redisKey = PREFIX + key;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.delete(redisKey);
            }
        } catch (Exception ignored) {
            // 释放失败不影响主流程，等待 TTL 自动过期
        }
        localCache.remove(redisKey);
    }

    public boolean isProcessed(String key) {
        String redisKey = PREFIX + key;
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                return Boolean.TRUE.equals(redis.hasKey(redisKey));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return localCache.containsKey(redisKey);
    }
}