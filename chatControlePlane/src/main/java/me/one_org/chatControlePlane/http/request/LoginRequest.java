package me.one_org.chatControlePlane.http.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "email is required")
    @Email(message = "invalid email format")
    String email,
    @NotBlank(message = "deviceId is required")
    String deviceId
) {
}
