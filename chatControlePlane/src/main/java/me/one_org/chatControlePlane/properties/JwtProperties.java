package me.one_org.chatControlePlane.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtProperties {
    private String secret;
    private String issuer;
    long accessTokenExpiration;
    long refreshTokenExpiration;
    long tempTokenExpiration;
}
