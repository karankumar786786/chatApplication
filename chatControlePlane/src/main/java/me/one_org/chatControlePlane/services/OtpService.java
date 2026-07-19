package me.one_org.chatControlePlane.services;

import me.one_org.chatControlePlane.cache.Cache;
import me.one_org.chatControlePlane.dtos.OtpData;
import me.one_org.chatControlePlane.utils.Mail;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

@Service
public class OtpService {
    private final Cache cache;
    private final Mail mail;
    private final PasswordEncoder encoder;
    private static final String OTP_PREFIX = "otp:";
    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final SecureRandom random = new SecureRandom();

    public OtpService(Cache cache, Mail mail, PasswordEncoder encoder) {
        this.cache = cache;
        this.mail = mail;
        this.encoder = encoder;
    }

    private String generateOtp() {
        int value = 100000 + random.nextInt(900000);
        return String.valueOf(value);
    }

    public void sendOtp(String email, OtpData data) {
        String otp = generateOtp();
        mail.sendMail(email, otp);
        String encodedOtp = encoder.encode(otp);
        OtpData cachedData = new OtpData(
                data.name(),
                data.email(),
                data.profilePicture(),
                data.description(),
                data.dob(),
                encodedOtp,
                0
        );
        cache.put(OTP_PREFIX + email, cachedData, OTP_TTL);
    }

    public Optional<OtpData> verifyOtp(String email, String rawOtp) {
        String key = OTP_PREFIX + email;
        Optional<OtpData> cachedDataOpt = cache.get(key, OtpData.class);
        if (cachedDataOpt.isEmpty()) {
            return Optional.empty();
        }
        OtpData cachedData = cachedDataOpt.get();
        if (encoder.matches(rawOtp, cachedData.encodedOtp())) {
            cache.delete(key);
            return Optional.of(cachedData);
        } else {
            int newAttempts = cachedData.attempts() + 1;
            if (newAttempts >= 3) {
                cache.delete(key);
            } else {
                OtpData updatedData = new OtpData(
                        cachedData.name(),
                        cachedData.email(),
                        cachedData.profilePicture(),
                        cachedData.description(),
                        cachedData.dob(),
                        cachedData.encodedOtp(),
                        newAttempts
                );
                cache.put(key, updatedData, OTP_TTL);
            }
            return Optional.empty();
        }
    }
}


