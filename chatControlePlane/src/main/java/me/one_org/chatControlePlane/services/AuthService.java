package me.one_org.chatControlePlane.services;

import com.nimbusds.jose.JOSEException;
import me.one_org.chatControlePlane.dtos.AuthData;
import me.one_org.chatControlePlane.dtos.GenerateTokenPayload;
import me.one_org.chatControlePlane.dtos.GeneratedToken;
import me.one_org.chatControlePlane.dtos.OtpData;
import me.one_org.chatControlePlane.dtos.DecodedTokenPayload;
import me.one_org.chatControlePlane.entitys.Session;
import me.one_org.chatControlePlane.entitys.User;
import me.one_org.chatControlePlane.enums.TokenType;
import me.one_org.chatControlePlane.errors.InvalidOtpException;
import me.one_org.chatControlePlane.http.request.LoginRequest;
import me.one_org.chatControlePlane.http.request.RegisterRequest;
import me.one_org.chatControlePlane.http.request.VerifyOtpRequest;
import me.one_org.chatControlePlane.http.response.LoginResponse;
import me.one_org.chatControlePlane.http.response.RegisterResponse;
import me.one_org.chatControlePlane.http.response.VerifyOtpResponse;
import me.one_org.chatControlePlane.properties.JwtProperties;
import me.one_org.chatControlePlane.utils.JsonWebToken;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {
    private final OtpService otpService;
    private final UserService userService;
    private final SessionService sessionService;
    private final JsonWebToken jsonWebToken;
    private final BlacklistService blacklistService;
    private final JwtProperties jwtProperties;

    public AuthService(OtpService otpService, UserService userService, SessionService sessionService, JsonWebToken jsonWebToken, BlacklistService blacklistService, JwtProperties jwtProperties) {
        this.otpService = otpService;
        this.userService = userService;
        this.sessionService = sessionService;
        this.jsonWebToken = jsonWebToken;
        this.blacklistService = blacklistService;
        this.jwtProperties = jwtProperties;
    }

    public RegisterResponse register(RegisterRequest request) throws JOSEException {
        GenerateTokenPayload tokenPayload = new GenerateTokenPayload(request.email(), request.email(), TokenType.TEMP_TOKEN);
        GeneratedToken tempToken = jsonWebToken.generateToken(tokenPayload);

        OtpData otpData = new OtpData(
                request.name(),
                request.email(),
                request.profilePicture(),
                request.description(),
                request.dob()
        );
        otpService.sendOtp(request.email(), otpData);

        return new RegisterResponse(tempToken.token());
    }

    public VerifyOtpResponse verifyOtp(VerifyOtpRequest request) throws JOSEException {
        DecodedTokenPayload tokenPayload = jsonWebToken.verify(request.tempToken(), TokenType.TEMP_TOKEN);
        String email = tokenPayload.subject();

        Optional<OtpData> otpDataOpt = otpService.verifyOtp(email, request.otp());
        if (otpDataOpt.isEmpty()) {
            throw new InvalidOtpException("Invalid or expired OTP");
        }
        OtpData otpData = otpDataOpt.get();

        User user = userService.findByEmail(otpData.email()).orElseGet(() -> {
            User newUser = User.builder()
                    .name(otpData.name())
                    .email(otpData.email())
                    .profilePicture(otpData.profilePicture())
                    .description(otpData.description())
                    .dob(otpData.dob())
                    .build();
            return userService.save(newUser);
        });

        GenerateTokenPayload accessPayload = new GenerateTokenPayload(user.getId(), request.deviceId(), TokenType.ACCESS_TOKEN);
        GeneratedToken accessToken = jsonWebToken.generateToken(accessPayload);

        GenerateTokenPayload refreshPayload = new GenerateTokenPayload(user.getId(), request.deviceId(), TokenType.REFRESH_TOKEN);
        GeneratedToken refreshToken = jsonWebToken.generateToken(refreshPayload);

        sessionService.createSession(
                user.getId(),
                accessToken.jwtId(),
                refreshToken.jwtId(),
                request.deviceId(),
                refreshToken.expiresAt().toInstant()
        );

        AuthData authData = new AuthData(accessToken.token(), refreshToken.token());
        return new VerifyOtpResponse(authData);
    }

    public LoginResponse login(LoginRequest request) throws JOSEException {
        Optional<User> userOpt = userService.findByEmail(request.email());
        if (userOpt.isPresent()) {
            sessionService.invalidateExistingSession(userOpt.get().getId(), request.deviceId());
        }

        GenerateTokenPayload tokenPayload = new GenerateTokenPayload(request.email(), request.email(), TokenType.TEMP_TOKEN);
        GeneratedToken tempToken = jsonWebToken.generateToken(tokenPayload);

        OtpData otpData = new OtpData(null, request.email(), null, null, null);
        otpService.sendOtp(request.email(), otpData);

        return new LoginResponse(tempToken.token());
    }

    public void logout(String accessTokenId) {
        Optional<Session> sessionOpt = sessionService.getSessionByAccessTokenId(accessTokenId);
        if (sessionOpt.isPresent()) {
            Session session = sessionOpt.get();
            long accessTtl = jwtProperties.getAccessTokenExpiration() / 1000;
            long refreshTtl = jwtProperties.getRefreshTokenExpiration() / 1000;
            
            blacklistService.blacklist(session.getAccessTokenId(), accessTtl);
            blacklistService.blacklist(session.getRefreshTokenId(), refreshTtl);
            
            sessionService.deleteSession(session.getId());
        }
    }
}
