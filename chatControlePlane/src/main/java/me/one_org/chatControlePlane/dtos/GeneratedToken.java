package me.one_org.chatControlePlane.dtos;



import java.util.Date;

public record GeneratedToken(
        String token,
        String jwtId,
        Date expiresAt
) {
}
