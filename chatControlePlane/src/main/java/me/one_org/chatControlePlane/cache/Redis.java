package me.one_org.chatControlePlane.cache;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class Redis implements Cache{
    private final RedisTemplate<String,Object> redisTemplate;

    public Redis(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        this.redisTemplate.opsForValue().set(key,value,ttl);
    }

    @Override
    public <T> Optional<T> get(String key,Class<T> type) {
       Object value = this.redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }


    @Override
    public void delete(String key) {
        this.redisTemplate.delete(key);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(this.redisTemplate.hasKey(key));
    }
}
