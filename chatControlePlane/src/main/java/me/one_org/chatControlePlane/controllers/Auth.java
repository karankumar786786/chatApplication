package me.one_org.chatControlePlane.controllers;

import com.nimbusds.jose.JOSEException;
import jakarta.validation.Valid;
import me.one_org.chatControlePlane.http.request.LoginRequest;
import me.one_org.chatControlePlane.http.request.RegisterRequest;
import me.one_org.chatControlePlane.http.request.VerifyOtpRequest;
import me.one_org.chatControlePlane.http.response.LoginResponse;
import me.one_org.chatControlePlane.http.response.RegisterResponse;
import me.one_org.chatControlePlane.http.response.VerifyOtpResponse;
import me.one_org.chatControlePlane.services.AuthService;
import me.one_org.chatControlePlane.dtos.DecodedTokenPayload;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class Auth {

    private final AuthService authService;

    public Auth(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) throws JOSEException {
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<VerifyOtpResponse> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request
    ) throws JOSEException {
        VerifyOtpResponse response = authService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) throws JOSEException {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal DecodedTokenPayload tokenPayload) {
        authService.logout(tokenPayload.jwtId());
        return ResponseEntity.noContent().build();
    }
}
