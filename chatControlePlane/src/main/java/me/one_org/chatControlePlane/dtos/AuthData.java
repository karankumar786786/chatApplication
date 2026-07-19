package me.one_org.chatControlePlane.dtos;

public record AuthData(
    String accessToken,
    String refreshToken
) {}
