package me.one_org.chatControlePlane.grpcServices;

import io.grpc.stub.StreamObserver;
import me.one_org.chatControlePlane.dtos.DecodedTokenPayload;
import me.one_org.chatControlePlane.entitys.Session;
import me.one_org.chatControlePlane.enums.TokenType;
import me.one_org.chatControlePlane.grpc.AuthenticationServiceGrpc;
import me.one_org.chatControlePlane.grpc.Request;
import me.one_org.chatControlePlane.grpc.Response;
import me.one_org.chatControlePlane.services.BlacklistService;
import me.one_org.chatControlePlane.services.SessionService;
import me.one_org.chatControlePlane.utils.Hmac;
import me.one_org.chatControlePlane.utils.JsonWebToken;
import org.springframework.grpc.server.service.GrpcService;

import java.util.Optional;

@GrpcService
public class GrpcAuthService extends AuthenticationServiceGrpc.AuthenticationServiceImplBase {

    private final JsonWebToken jsonWebToken;
    private final BlacklistService blacklistService;
    private final SessionService sessionService;
    private final Hmac hmac;

    public GrpcAuthService(JsonWebToken jsonWebToken, BlacklistService blacklistService, SessionService sessionService, Hmac hmac) {
        this.jsonWebToken = jsonWebToken;
        this.blacklistService = blacklistService;
        this.sessionService = sessionService;
        this.hmac = hmac;
    }

    @Override
    public void authenticate(Request request, StreamObserver<Response> responseObserver) {
        try {
            String accessToken = request.getAccessToken();
            String deviceId = request.getDeviceId();

            DecodedTokenPayload payload = jsonWebToken.verify(accessToken, TokenType.ACCESS_TOKEN);

            if (payload.deviceId() == null || !payload.deviceId().equals(deviceId)) {
                throw new RuntimeException("Device ID mismatch");
            }
            
            if (blacklistService.isBlacklisted(payload.jwtId())) {
                throw new RuntimeException("Token is blacklisted");
            }
            
            Optional<Session> sessionOpt = sessionService.getSessionByAccessTokenId(payload.jwtId());
            if (sessionOpt.isEmpty()) {
                throw new RuntimeException("No active session found");
            }
            
            Session session = sessionOpt.get();
            
            if (!hmac.generateHmac(deviceId).equals(session.getHashedDeviceId())) {
                throw new RuntimeException("Session device mismatch");
            }

            Response response = Response.newBuilder()
                    .setSubject(payload.subject())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    io.grpc.Status.UNAUTHENTICATED
                            .withDescription("Invalid token: " + e.getMessage())
                            .asRuntimeException()
            );
        }
    }
}

