package com.znxsgl.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用 Redis 缓存服务。
 *
 * - Redis 开启时：优先使用 Redis，多实例共享；
 * - Redis 关闭或异常时：自动降级到 JVM 本地缓存，保证单机可用；
 * - 缓存 value 统一为 JSON 字符串，业务层自己负责序列化/反序列化。
 */
@Service
public class RedisCacheService {

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Map<String, LocalValue> localCache = new ConcurrentHashMap<>();

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    public RedisCacheService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    public String get(String key) {
        if (redisEnabled) {
            try {
                StringRedisTemplate redis = redisProvider.getIfAvailable();
                if (redis != null) {
                    String value = redis.opsForValue().get(key);
                    if (value != null) {
                        return value;
                    }
                }
            } catch (Exception ignored) {
                // Redis 异常时降级本地缓存
            }
        }
        LocalValue local = localCache.get(key);
        if (local == null) {
            return null;
        }
        if (local.expireAt < System.currentTimeMillis()) {
            localCache.remove(key);
            return null;
        }
        return local.value;
    }

    public void put(String key, String json, Duration ttl) {
        if (redisEnabled) {
            try {
                StringRedisTemplate redis = redisProvider.getIfAvailable();
                if (redis != null) {
                    redis.opsForValue().set(key, json, ttl);
                    return;
                }
            } catch (Exception ignored) {
                // Redis 异常时降级本地缓存
            }
        }
        localCache.put(key, new LocalValue(json, System.currentTimeMillis() + ttl.toMillis()));
    }

    public void evict(String key) {
        if (redisEnabled) {
            try {
                StringRedisTemplate redis = redisProvider.getIfAvailable();
                if (redis != null) {
                    redis.delete(key);
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        localCache.remove(key);
    }

    public void evictByPrefix(String prefix) {
        if (redisEnabled) {
            try {
                StringRedisTemplate redis = redisProvider.getIfAvailable();
                if (redis != null) {
                    var keys = redis.keys(prefix + "*");
                    if (keys != null && !keys.isEmpty()) {
                        redis.delete(keys);
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        localCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private record LocalValue(String value, long expireAt) {}
}