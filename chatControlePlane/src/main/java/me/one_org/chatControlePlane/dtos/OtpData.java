package me.one_org.chatControlePlane.dtos;

import java.time.LocalDate;

public record OtpData(
        String name,
        String email,
        String profilePicture,
        String description,
        LocalDate dob,
        String encodedOtp,
        int attempts
) {
    public OtpData(String name, String email, String profilePicture, String description, LocalDate dob) {
        this(name, email, profilePicture, description, dob, null, 0);
    }
}

