package com.reconciliation.connection.repository;

import com.reconciliation.connection.entity.ProviderConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProviderConnectionRepository extends JpaRepository<ProviderConnection, Long> {

    List<ProviderConnection> findByMerchantIdOrderByProviderAsc(String merchantId);

    Optional<ProviderConnection> findByMerchantIdAndProvider(String merchantId, String provider);

    List<ProviderConnection> findByProviderAndStatus(String provider, com.reconciliation.common.enums.ConnectionStatus status);
}
