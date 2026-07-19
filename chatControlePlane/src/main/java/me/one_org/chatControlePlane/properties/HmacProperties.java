package me.one_org.chatControlePlane.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hmac")
@Data
public class HmacProperties {
    private String secret;
}
