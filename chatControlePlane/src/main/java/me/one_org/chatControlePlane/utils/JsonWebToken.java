package me.one_org.chatControlePlane.utils;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.Valid;
import me.one_org.chatControlePlane.dtos.DecodedTokenPayload;
import me.one_org.chatControlePlane.dtos.GenerateTokenPayload;
import me.one_org.chatControlePlane.dtos.GeneratedToken;
import me.one_org.chatControlePlane.enums.TokenType;
import me.one_org.chatControlePlane.properties.JwtProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

@Component
@Validated
public class JsonWebToken {

    private final JwtProperties properties;
    private SecretKey secretKey;

    public JsonWebToken(JwtProperties properties) {
        this.properties = properties;
    }

    private SecretKey getSecretKey() {
        if (this.secretKey == null) {
            synchronized (this) {
                if (this.secretKey == null) {
                    String secret = properties.getSecret();
                    if (secret == null || secret.isEmpty()) {
                        throw new IllegalStateException("JWT secret is not configured");
                    }
                    this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                }
            }
        }
        return this.secretKey;
    }

    public GeneratedToken generateToken(@Valid GenerateTokenPayload payload) throws JOSEException {
        Instant now = Instant.now();
        long expirationTime = properties.getTempTokenExpiration();
        if (payload.type() == TokenType.ACCESS_TOKEN) {
            expirationTime = properties.getAccessTokenExpiration();
        }
        if (payload.type() == TokenType.REFRESH_TOKEN) {
            expirationTime = properties.getRefreshTokenExpiration();
        };
        String jwtId = UUID.randomUUID().toString();
        Date expiresAt = Date.from(now.plusMillis(expirationTime));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(payload.subject())
                .issuer(properties.getIssuer())
                .claim("deviceId", payload.deviceId())
                .issueTime(Date.from(now))
                .expirationTime(expiresAt)
                .jwtID(jwtId)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(getSecretKey()));
        String token = jwt.serialize();
        return new GeneratedToken(token,jwtId,expiresAt);
    }

    public DecodedTokenPayload verify(String token, TokenType type) throws JOSEException {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            MACVerifier verifier = new MACVerifier(getSecretKey());
            if (!signedJWT.verify(verifier)) {
                throw new JOSEException("invalid jwt token signature");
            }
            JWTClaimsSet claimsSets = signedJWT.getJWTClaimsSet();
            String subject = claimsSets.getSubject();
            String issuer = claimsSets.getIssuer();
            if (!Objects.equals(issuer, this.properties.getIssuer())) {
                throw new JOSEException("invalid issuer");
            };
            String deviceId = claimsSets.getClaimAsString("deviceId");
            String jwtId = claimsSets.getJWTID();
            return DecodedTokenPayload.builder()
                    .subject(subject)
                    .jwtId(jwtId)
                    .deviceId(deviceId)
                    .build();
        } catch (Exception e) {
            throw new JOSEException("failed to parse or verify token" + e);
        }
    }

}
