package com.znxsgl.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 分布式限流：多实例共享计数，避免单机 Guava RateLimiter 在多实例下失效。
 *
 * 说明：
 * - 采用固定窗口计数（Lua 保证 INCR + EXPIRE 原子性）；
 * - Redis 不可用时返回 null，由调用方回退到本地 Guava RateLimiter；
 * - 阈值可通过常量调整，建议后续迁移到配置中心。
 */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisRateLimiterService {

    private static final String GLOBAL_PREFIX = "znxsgl:rate:llm:global:";
    private static final String USER_PREFIX = "znxsgl:rate:llm:user:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final DefaultRedisScript<Long> counterScript;

    public RedisRateLimiterService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
        this.counterScript = new DefaultRedisScript<>();
        this.counterScript.setResultType(Long.class);
        this.counterScript.setScriptText(
                "local current = redis.call('INCR', KEYS[1]);" +
                "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]); end;" +
                "if current > tonumber(ARGV[1]) then return 0 else return 1 end;"
        );
    }

    /**
     * 全局 AI 限流：默认 20 次/秒。
     *
     * @return true=放行；false=限流；null=Redis 不可用，调用方回退本地限流
     */
    public Boolean tryAcquireGlobal() {
        long second = System.currentTimeMillis() / 1000;
        return tryAcquire(GLOBAL_PREFIX + second, 20L, 2L);
    }

    /**
     * 单用户 AI 限流：默认 10 次/分钟。
     *
     * @return true=放行；false=限流；null=Redis 不可用，调用方回退本地限流
     */
    public Boolean tryAcquireUser(Long userId) {
        if (userId == null) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60000;
        return tryAcquire(USER_PREFIX + userId + ":" + minute, 10L, 120L);
    }

    private Boolean tryAcquire(String key, long limit, long ttlSeconds) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return null;
            }
            Long result = redis.execute(counterScript, List.of(key),
                    String.valueOf(limit), String.valueOf(ttlSeconds));
            return result != null && result == 1L;
        } catch (Exception ignored) {
            return null;
        }
    }
}