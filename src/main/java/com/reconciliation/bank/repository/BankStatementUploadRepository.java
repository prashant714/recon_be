package com.reconciliation.bank.repository;

import com.reconciliation.bank.entity.BankStatementUpload;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BankStatementUploadRepository extends JpaRepository<BankStatementUpload, Long> {

    Optional<BankStatementUpload> findByMerchantIdAndUploadId(String merchantId, String uploadId);

    Page<BankStatementUpload> findByMerchantId(String merchantId, Pageable pageable);
}
