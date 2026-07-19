package me.one_org.chatControlePlane.utils;

import me.one_org.chatControlePlane.properties.HmacProperties;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.stereotype.Component;

@Component
public class Hmac {
    private final HmacUtils utils;
    public Hmac(HmacProperties properties) {
        utils = new HmacUtils("HmacSHA256", properties.getSecret());
    }

    public String generateHmac(String message){
        String hashedData = this.utils.hmacHex(message.getBytes());
        return message + ":" + hashedData;
    }

    public boolean verify(String hash) {
        if (!hash.contains(":")) {
            return false;
        }
        String[] data = hash.split(":");
        String generatedHash = generateHmac(data[0]);
        return generatedHash.equals(data[1]);
    }
}
