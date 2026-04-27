package com.reconciliation.user.repository;

import com.reconciliation.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByMerchantIdAndEmail(String merchantId, String email);

    Optional<User> findByMerchantIdAndPhone(String merchantId, String phone);

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
}
