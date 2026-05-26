package com.reconciliation.settlement.repository;

import com.reconciliation.common.enums.ReportLineMatchStatus;
import com.reconciliation.settlement.entity.SettlementReportLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SettlementReportLineRepository extends JpaRepository<SettlementReportLine, Long> {

    List<SettlementReportLine> findBySettlementIdAndMatchStatus(Long settlementId, ReportLineMatchStatus matchStatus);

    List<SettlementReportLine> findByMatchStatus(ReportLineMatchStatus matchStatus);

    Optional<SettlementReportLine> findBySettlementIdAndProviderTxnId(Long settlementId, String providerTxnId);

    Optional<SettlementReportLine> findFirstByProviderAndProviderTxnId(String provider, String providerTxnId);

    boolean existsBySettlementIdAndProviderTxnId(Long settlementId, String providerTxnId);

    long countBySettlementId(Long settlementId);

    long countBySettlementIdAndMatchStatus(Long settlementId, ReportLineMatchStatus matchStatus);
}
