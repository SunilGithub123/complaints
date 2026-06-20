package com.example.complaints.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Persisted staff refresh-token record. Backed by {@code refresh_token}.
 *
 * <p>The {@link #tokenHash} is SHA-256 of the raw JWT (never the JWT itself), so a leak of the
 * DB does not allow forging access tokens.</p>
 *
 * <p>Consumers do NOT use refresh tokens — the consumer verification JWT is short-lived and not
 * persisted (see TECHNICAL_DESIGN.md 6).</p>
 */
@Entity
@Table(name = "refresh_token")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    /** Set by DB DEFAULT now() on INSERT; never modified after. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Domain field — explicitly set by the service on every refresh use. */
    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;
}
