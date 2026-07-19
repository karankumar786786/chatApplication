package me.one_org.chatControlePlane.cache;

import java.time.Duration;
import java.util.Optional;

public interface Cache {
    <T> void put(String key, T value, Duration ttl);
    <T> Optional<T> get(String key,Class<T> type);
    void delete(String key);
    boolean exists(String key);
}
