package me.one_org.chatControlePlane.services;

import me.one_org.chatControlePlane.entitys.User;
import me.one_org.chatControlePlane.repositorys.UserRepository;
import me.one_org.chatControlePlane.utils.Hmac;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Optional;

@Service
public class UserService {
    private final Hmac hmac;
    private final UserRepository userRepository;

    public UserService(Hmac hmac, UserRepository userRepository) {
        this.hmac = hmac;
        this.userRepository = userRepository;
    }
    public User save(User user){
        String id = hmac.generateHmac(UUID.randomUUID().toString());
        user.setId(id);
        return userRepository.save(user);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
}