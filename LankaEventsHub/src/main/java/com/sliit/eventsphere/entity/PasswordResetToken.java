package com.sliit.eventsphere.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A password reset token, stored as a SHA-256 hash of the value that was
 * put in the link.
 *
 * The raw token is a bearer credential: anyone holding it can change that
 * account's password. Storing it verbatim means a leaked database dump -
 * or a careless SELECT in a shared MySQL session - hands over live reset
 * links for every pending request. Hashing it makes the stored row
 * useless on its own, exactly as password_hash does for passwords. It is
 * hashed rather than encrypted because nothing ever needs to read the
 * original back: verification only has to answer "does the token in this
 * URL hash to a row we hold?".
 *
 * A plain SHA-256 (not BCrypt) is the right choice here: the input is 256
 * bits of SecureRandom output, so there is no dictionary to attack and no
 * work factor worth paying on every lookup.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 of the raw token, hex-encoded. Never the token itself. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public PasswordResetToken() {
    }

    /**
     * @param tokenHash the hashed token - callers must pass the output of
     *                  PasswordResetService.hash(...), never a raw value.
     */
    public PasswordResetToken(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Transient
    public boolean isValid() {
        return !used && LocalDateTime.now().isBefore(expiresAt);
    }
}
