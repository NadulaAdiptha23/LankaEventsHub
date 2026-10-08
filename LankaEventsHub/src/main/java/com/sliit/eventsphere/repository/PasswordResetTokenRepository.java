package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * Looked up by the HASH of the token in the link, never by the token
     * itself - the raw value is not stored anywhere, so there is nothing
     * else it could be matched against.
     */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * The same lookup, holding a write lock on the token row.
     *
     * A reset token is single-use, and "single" has to survive two
     * requests arriving at once. Without the lock both can read the token
     * as unused, both can pass the validity check, and both can set a
     * password - so whoever wrote second wins, which is not a race
     * anybody should be able to run against an account recovery flow.
     *
     * With the lock the second request blocks until the first commits,
     * then reads the token as used and is refused.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Every unused token this user holds. After a successful reset all of
     * them are marked used, so an older link that was sent earlier (or
     * intercepted) cannot still change the password afterwards.
     */
    List<PasswordResetToken> findByUserIdAndUsedFalse(Long userId);

    /** Every reset token issued to one person - for a permanent delete. */
    List<PasswordResetToken> findByUserId(Long userId);
}
