package com.reconciliation.user.service;

import com.reconciliation.common.enums.TransactionStatus;
import com.reconciliation.user.entity.User;
import com.reconciliation.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserIdentityService {

    private final UserRepository userRepository;
    private final UserPersistenceService userPersistenceService;

    /**
     * Find or create a user by email or phone.
     * Uses SERIALIZABLE isolation to prevent duplicate user creation
     * under concurrent ingestion.
     *
     * Returns null if neither email nor phone is provided (e.g. UPI VPA only).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public Long resolveUserId(String merchantId, String email,
                              String phone, String name) {
        if ((email == null || email.isBlank()) &&
            (phone == null || phone.isBlank())) {
            return null; // UPI-only — no stable identity to resolve
        }

        String normalizedEmail = normalize(email);
        String normalizedPhone = normalizePhone(phone);

        lockIdentityKeys(merchantId, normalizedEmail, normalizedPhone);

        // 1. Try by email first
        if (normalizedEmail != null) {
            Optional<User> byEmail = userRepository
                    .findByMerchantIdAndEmail(merchantId, normalizedEmail);
            if (byEmail.isPresent()) {
                User user = byEmail.get();
                enrichUser(user, normalizedPhone, name);
                updateLastSeen(user);
                return user.getId();
            }
        }

        // 2. Try by phone
        if (normalizedPhone != null) {
            Optional<User> byPhone = userRepository
                    .findByMerchantIdAndPhone(merchantId, normalizedPhone);
            if (byPhone.isPresent()) {
                User u = byPhone.get();
                enrichUser(u, normalizedEmail, name);
                updateLastSeen(u);
                return u.getId();
            }
        }

        // 3. Create new user
        return createUser(merchantId, normalizedEmail, normalizedPhone, name);
    }

    /**
     * Increment transaction aggregates on user.
     * Called after a transaction is successfully upserted.
     */
    @Transactional
    public void incrementAggregates(Long userId, long amount, boolean failed) {
        if (userId == null) return;
        userRepository.incrementAggregates(userId, amount, failed ? 1 : 0);
    }

    @Transactional
    public void refreshAggregates(Long userId) {
        if (userId == null) return;
        userRepository.refreshAggregates(userId);
    }

    private Long createUser(String merchantId, String email,
                            String phone, String name) {
        try {
            Long userId = userPersistenceService.createUser(merchantId, email, phone, name);
            log.info("Created new user id={} merchantId={}", userId, merchantId);
            return userId;

        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race condition: another thread created this user simultaneously
            return userPersistenceService.findExistingUserId(merchantId, email, phone);
        }
    }

    private void updateLastSeen(User user) {
        user.setLastSeenAt(OffsetDateTime.now());
        userRepository.save(user);
    }

    private void enrichUser(User user, String alternateIdentity, String name) {
        if (!StringUtils.hasText(user.getEmail()) && alternateIdentity != null && alternateIdentity.contains("@")) {
            user.setEmail(alternateIdentity);
        }
        if (!StringUtils.hasText(user.getPhone()) && alternateIdentity != null && !alternateIdentity.contains("@")) {
            user.setPhone(alternateIdentity);
        }
        if (!StringUtils.hasText(user.getName()) && StringUtils.hasText(name)) {
            user.setName(name.trim());
        }
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) return null;
        return email.toLowerCase().trim();
    }

    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        // Strip spaces, dashes, dots — keep leading + for international
        String cleaned = phone.replaceAll("[\\\\s\\\\-\\\\.]", "").trim();
        // Ensure Indian numbers have +91 prefix
        if (cleaned.startsWith("0")) cleaned = "+91" + cleaned.substring(1);
        if (cleaned.length() == 10) cleaned = "+91" + cleaned;
        return cleaned;
    }

    private void lockIdentityKeys(String merchantId, String email, String phone) {
        List<String> keys = new ArrayList<>();
        if (StringUtils.hasText(email)) {
            keys.add("email:" + email);
        }
        if (StringUtils.hasText(phone)) {
            keys.add("phone:" + phone);
        }
        keys.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(key -> userRepository.lockIdentityKey(merchantId, key));
    }
}
