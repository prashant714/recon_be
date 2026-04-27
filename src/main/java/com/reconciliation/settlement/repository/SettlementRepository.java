package com.reconciliation.settlement.repository;

import com.reconciliation.settlement.entity.Settlement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByProviderAndProviderSettlementId(String provider, String providerSettlementId);

    List<Settlement> findByMerchantId(String merchantId);

    List<Settlement> findBySettlementStatus(com.reconciliation.common.enums.SettlementStatus settlementStatus);

    List<Settlement> findBySettledAtBetween(OffsetDateTime from, OffsetDateTime to);

    List<Settlement> findBySettlementStatusAndCreatedAtBefore(
            com.reconciliation.common.enums.SettlementStatus status, OffsetDateTime before);
}
