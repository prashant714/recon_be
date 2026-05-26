package com.reconciliation.merchant.repository;

import com.reconciliation.merchant.entity.Merchant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByMerchantId(String merchantId);

    Optional<Merchant> findByEmail(String email);

    List<Merchant> findByStatus(String status);

    boolean existsByMerchantId(String merchantId);

    boolean existsByEmail(String email);
}
