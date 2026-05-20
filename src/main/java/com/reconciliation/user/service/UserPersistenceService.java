package com.reconciliation.user.service;

import com.reconciliation.user.entity.User;
import com.reconciliation.user.repository.UserRepository;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserPersistenceService {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createUser(String merchantId, String email, String phone, String name) {
        User user = User.builder()
                .merchantId(merchantId)
                .email(email)
                .phone(phone)
                .name(name)
                .firstSeenAt(OffsetDateTime.now())
                .lastSeenAt(OffsetDateTime.now())
                .totalTxnCount(0)
                .totalTxnAmount(0L)
                .failedTxnCount(0)
                .distinctPaymentMethods(0)
                .build();

        User saved = userRepository.save(user);
        return saved.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Long findExistingUserId(String merchantId, String email, String phone) {
        if (email != null) {
            Long id = userRepository.findByMerchantIdAndEmail(merchantId, email)
                    .map(User::getId)
                    .orElse(null);
            if (id != null) {
                return id;
            }
        }
        if (phone != null) {
            return userRepository.findByMerchantIdAndPhone(merchantId, phone)
                    .map(User::getId)
                    .orElse(null);
        }
        return null;
    }
}
