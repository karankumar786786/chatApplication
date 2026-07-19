package me.one_org.chatControlePlane.repositorys;

import me.one_org.chatControlePlane.entitys.Session;
import org.springframework.data.cassandra.repository.AllowFiltering;
import org.springframework.data.cassandra.repository.CassandraRepository;
// import org.springframework.stereotype.Repository;

import java.util.Optional;

// @Repository
public interface SessionRepository extends CassandraRepository<Session,String> {
    
    @AllowFiltering
    Optional<Session> findByUserIdAndHashedDeviceId(String userId, String hashedDeviceId);
    
    @AllowFiltering
    Optional<Session> findByAccessTokenId(String accessTokenId);
}
