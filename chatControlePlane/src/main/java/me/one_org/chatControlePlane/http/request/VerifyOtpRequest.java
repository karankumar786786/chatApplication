package me.one_org.chatControlePlane.http.request;

import jakarta.validation.constraints.NotBlank;

public record VerifyOtpRequest(
    @NotBlank(message = "tempToken is required")
    String tempToken,
    @NotBlank(message = "otp is required")
    String otp,
    @NotBlank(message = "deviceId is required")
    String deviceId
) {
}
