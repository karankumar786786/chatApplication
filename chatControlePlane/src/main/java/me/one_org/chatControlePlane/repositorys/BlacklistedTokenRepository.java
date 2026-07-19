package me.one_org.chatControlePlane.repositorys;

import me.one_org.chatControlePlane.entitys.BlacklistedToken;
import org.springframework.data.cassandra.repository.CassandraRepository;
// import org.springframework.stereotype.Repository;

// @Repository
public interface BlacklistedTokenRepository extends CassandraRepository<BlacklistedToken, String> {
}
