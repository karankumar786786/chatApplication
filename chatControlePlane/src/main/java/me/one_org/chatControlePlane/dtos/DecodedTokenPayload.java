package me.one_org.chatControlePlane.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record DecodedTokenPayload(
        @NotBlank
        String subject,
        @NotBlank
        String jwtId,
        @NotBlank
        String deviceId
) {

}
