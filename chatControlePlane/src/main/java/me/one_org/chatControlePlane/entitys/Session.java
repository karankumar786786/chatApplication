package me.one_org.chatControlePlane.entitys;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("sessions")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Session {
    @PrimaryKey
    private String id;
    @NotBlank(message = "userId is required")
    private String userId;
    @NotBlank(message = "accessTokenId is required")
    private String accessTokenId;
    @NotBlank(message = "refreshTokenId is reqired")
    private String refreshTokenId;
    @NotBlank(message = "hashed deviceId is required")
    private String hashedDeviceId;
    @NotBlank(message = "expiresAt is required")
    private Instant expireAt;
}
