package me.one_org.chatControlePlane.services;

import me.one_org.chatControlePlane.entitys.Session;
import me.one_org.chatControlePlane.repositorys.SessionRepository;
import me.one_org.chatControlePlane.utils.Hmac;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {
    private final SessionRepository sessionRepository;
    private final Hmac hmac;
    private final CassandraOperations cassandraOperations;

    public SessionService(SessionRepository sessionRepository, Hmac hmac, CassandraOperations cassandraOperations) {
        this.sessionRepository = sessionRepository;
        this.hmac = hmac;
        this.cassandraOperations = cassandraOperations;
    }

    @CachePut(value = "sessions", key = "#result.id")
    public Session createSession(String userId, String accessTokenId, String refreshTokenId, String deviceId, Instant expiresAt) {
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setAccessTokenId(accessTokenId);
        session.setRefreshTokenId(refreshTokenId);
        session.setHashedDeviceId(hmac.generateHmac(deviceId));
        session.setExpireAt(expiresAt);

        long ttlSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
        if (ttlSeconds <= 0) ttlSeconds = 1;

        InsertOptions insertOptions = InsertOptions.builder()
                .ttl((int) ttlSeconds)
                .build();

        cassandraOperations.insert(session, insertOptions);
        return session;
    }

    @Cacheable(value = "sessions", key = "#sessionId", unless = "#result == null")
    public Session getSession(String sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    public Optional<Session> getSessionByAccessTokenId(String accessTokenId) {
        return sessionRepository.findByAccessTokenId(accessTokenId);
    }

    @CacheEvict(value = "sessions", key = "#sessionId")
    public void deleteSession(String sessionId) {
        sessionRepository.deleteById(sessionId);
    }

    public void invalidateExistingSession(String userId, String deviceId) {
        String hashedDevice = hmac.generateHmac(deviceId);
        Optional<Session> existingSessionOpt = sessionRepository.findByUserIdAndHashedDeviceId(userId, hashedDevice);
        
        // Note: the findByUserIdAndHashedDeviceId method doesn't work perfectly with generateHmac() because 
        // generateHmac appends a new prefix each time, but wait...
        // Ah, Hmac generateHmac(message) returns `message + ":" + hashedData`. 
        // We can't query by it directly if the salt/hash is dynamic. 
        // But HmacUtils without a salt per-request produces a deterministic hash for a given message and secret.
        // So `hmac.generateHmac(deviceId)` IS deterministic and CAN be queried!
        
        existingSessionOpt.ifPresent(session -> deleteSession(session.getId()));
    }
}

