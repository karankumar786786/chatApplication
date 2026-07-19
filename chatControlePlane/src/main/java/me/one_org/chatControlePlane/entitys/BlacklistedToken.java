package me.one_org.chatControlePlane.entitys;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("blacklisted_tokens")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BlacklistedToken {
    @PrimaryKey
    private String jwtId;
    private Instant expiresAt;
}
