package me.one_org.chatControlePlane.http.response;

import me.one_org.chatControlePlane.dtos.AuthData;

public record VerifyOtpResponse(
    AuthData authData
) {
    
}
