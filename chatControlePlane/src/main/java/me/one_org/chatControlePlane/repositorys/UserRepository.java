package me.one_org.chatControlePlane.repositorys;

import me.one_org.chatControlePlane.entitys.User;
import org.springframework.data.cassandra.repository.AllowFiltering;
import org.springframework.data.cassandra.repository.CassandraRepository;
// import org.springframework.stereotype.Repository;

import java.util.Optional;

// @Repository
public interface UserRepository extends CassandraRepository<User, String> {
    
    @AllowFiltering
    Optional<User> findByEmail(String email);
}
