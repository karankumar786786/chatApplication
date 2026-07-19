package me.one_org.chatControlePlane.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import me.one_org.chatControlePlane.enums.TokenType;

public record GenerateTokenPayload(
        @NotBlank
        String subject,
        @NotBlank
        String deviceId,
        @NotNull
        TokenType type
) {}