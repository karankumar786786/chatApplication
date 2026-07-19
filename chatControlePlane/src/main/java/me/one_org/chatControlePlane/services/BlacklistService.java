package me.one_org.chatControlePlane.services;

import me.one_org.chatControlePlane.entitys.BlacklistedToken;
import me.one_org.chatControlePlane.repositorys.BlacklistedTokenRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class BlacklistService {
    private final BlacklistedTokenRepository repository;
    private final CassandraOperations cassandraOperations;

    public BlacklistService(BlacklistedTokenRepository repository, CassandraOperations cassandraOperations) {
        this.repository = repository;
        this.cassandraOperations = cassandraOperations;
    }

    public void blacklist(String jwtId, long ttlSeconds) {
        BlacklistedToken token = new BlacklistedToken(jwtId, Instant.now().plusSeconds(ttlSeconds));
        
        InsertOptions insertOptions = InsertOptions.builder()
                .ttl((int) ttlSeconds)
                .build();
                
        cassandraOperations.insert(token, insertOptions);
        // We do not eagerly cache it; it will be cached upon next read via isBlacklisted if needed,
        // or we could use @CachePut, but @Cacheable returns boolean so @CachePut would need a boolean return.
    }

    @Cacheable(value = "blacklist", key = "#jwtId")
    public boolean isBlacklisted(String jwtId) {
        return repository.existsById(jwtId);
    }

    @CacheEvict(value = "blacklist", key = "#jwtId")
    public void evictFromCache(String jwtId) {
        // Only evicts from cache
    }
}
