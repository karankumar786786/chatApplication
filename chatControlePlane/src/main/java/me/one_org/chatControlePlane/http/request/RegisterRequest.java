package me.one_org.chatControlePlane.http.request;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterRequest(
    @NotBlank(message = "name cannot be blank")
    String name,
    @NotBlank(message = "email cannot be blank")
    @Email
    String email,
    String profilePicture,
    @NotBlank(message = "description is required")
    String description,
    @NotNull(message = "dob cannot be null")
    LocalDate dob
) {
}
