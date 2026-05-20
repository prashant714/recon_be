package com.reconciliation.user.repository;

import com.reconciliation.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByMerchantIdAndEmail(String merchantId, String email);

    Optional<User> findByMerchantIdAndPhone(String merchantId, String phone);

    @Query(value = """
        SELECT 1
        FROM pg_advisory_xact_lock(hashtext(:merchantId), hashtext(:identityKey))
        """, nativeQuery = true)
    Integer lockIdentityKey(
        @Param("merchantId") String merchantId,
        @Param("identityKey") String identityKey
    );

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        UPDATE User u SET
            u.totalTxnCount  = u.totalTxnCount + 1,
            u.totalTxnAmount = u.totalTxnAmount + :amount,
            u.failedTxnCount = u.failedTxnCount + :failedIncrement,
            u.lastSeenAt     = CURRENT_TIMESTAMP,
            u.updatedAt      = CURRENT_TIMESTAMP
        WHERE u.id = :userId
    """)
    void incrementAggregates(
        @org.springframework.data.repository.query.Param("userId") Long userId,
        @org.springframework.data.repository.query.Param("amount") long amount,
        @org.springframework.data.repository.query.Param("failedIncrement") int failedIncrement
    );

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
        UPDATE users u SET
            total_txn_count = (
                SELECT COUNT(*)
                FROM transactions t
                WHERE t.user_id = :userId
                  AND t.event_type = 'PAYMENT'
            ),
            total_txn_amount = (
                SELECT COALESCE(SUM(t.presentment_amount), 0)
                FROM transactions t
                WHERE t.user_id = :userId
                  AND t.event_type = 'PAYMENT'
                  AND t.status = 'CAPTURED'
            ),
            failed_txn_count = (
                SELECT COUNT(*)
                FROM transactions t
                WHERE t.user_id = :userId
                  AND t.event_type = 'PAYMENT'
                  AND t.status = 'FAILED'
            ),
            distinct_payment_methods = (
                SELECT COUNT(DISTINCT t.payment_method)
                FROM transactions t
                WHERE t.user_id = :userId
                  AND t.payment_method IS NOT NULL
            ),
            last_seen_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE u.id = :userId
        """, nativeQuery = true)
    void refreshAggregates(@org.springframework.data.repository.query.Param("userId") Long userId);
}
